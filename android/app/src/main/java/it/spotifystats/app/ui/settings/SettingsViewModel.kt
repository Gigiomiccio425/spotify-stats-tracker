package it.spotifystats.app.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.SessionExpiredException
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.ImportJob
import it.spotifystats.app.data.api.Me
import it.spotifystats.app.data.api.SettingsPatch
import it.spotifystats.app.data.update.UpdateChecker
import it.spotifystats.app.data.update.UpdateStatus
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/** Quanto spesso si chiede al server a che punto è l'import. */
private const val POLL_INTERVAL_MS = 2000L

/** Oltre questi tentativi falliti di fila si smette di seguire il job. */
private const val MAX_POLL_FAILURES = 15

data class ImportProgress(
    val running: Boolean = false,
    val message: String? = null,
)

/**
 * Il corpo della richiesta è il file scelto dall'utente, copiato dal disco al
 * socket senza passare per la memoria.
 *
 * Il flusso viene riaperto a ogni scrittura invece di essere tenuto: OkHttp può
 * dover rimandare il corpo (un redirect, una connessione caduta e ripresa), e
 * un flusso già consumato manderebbe un file vuoto.
 */
private class UriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val size: Long,
) : RequestBody() {

    override fun contentType() = "application/json".toMediaType()

    override fun contentLength() = size

    override fun writeTo(sink: BufferedSink) {
        val stream = resolver.openInputStream(uri) ?: throw IOException("File non leggibile")
        stream.source().use { sink.writeAll(it) }
    }
}

class SettingsViewModel(private val repository: StatsRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<Me>>(UiState.Loading)
    val state: StateFlow<UiState<Me>> = _state.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    private val _updateStatus = MutableStateFlow<UpdateStatus?>(null)
    val updateStatus: StateFlow<UpdateStatus?> = _updateStatus.asStateFlow()

    private val _backendVersion = MutableStateFlow<String?>(null)
    val backendVersion: StateFlow<String?> = _backendVersion.asStateFlow()

    init {
        load()
        resumeRunningImport()
        checkForUpdate()
        loadBackendVersion()
    }

    /**
     * Guarda su GitHub se esiste una versione più recente.
     *
     * Parte da solo all'apertura della schermata: un aggiornamento che va
     * cercato a mano non viene cercato mai.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            _updateStatus.value = UpdateStatus.Checking
            _updateStatus.value = UpdateChecker.check()
        }
    }

    /**
     * Serve a rispondere a "il server sta girando la versione nuova?". Senza un
     * numero visibile, dopo un aggiornamento dell'immagine si può solo sperare.
     */
    private fun loadBackendVersion() {
        viewModelScope.launch {
            _backendVersion.value = repository.health().getOrNull()?.version
        }
    }

    /**
     * L'import gira sul server: chiudere l'app non lo ferma. Riaprendo questa
     * schermata si torna a seguirlo, invece di mostrare un pulsante che
     * risponderebbe "un import è già in corso".
     */
    private fun resumeRunningImport() {
        viewModelScope.launch {
            val open = repository.importJobs().getOrNull()?.jobs?.firstOrNull { !it.finished }
                ?: return@launch

            report(open.filename, describe(open))
            val job = awaitJob(open.id) { report(open.filename, describe(it)) }.getOrNull()

            _importProgress.value = ImportProgress(
                running = false,
                message = when {
                    job == null -> "Import di ${open.filename}: stato non recuperabile"
                    job.status == "error" -> "${open.filename}: ${job.error ?: "import non riuscito"}"
                    else -> summary(job.rowsImported, job.rowsDuplicate) +
                        (job.warning?.let { "\n$it" } ?: "")
                },
            )
            load()
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.me().fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.message ?: "Impossibile caricare il profilo") },
            )
        }
    }

    fun setPeriodMode(mode: String) {
        val current = (_state.value as? UiState.Ready)?.data ?: return
        viewModelScope.launch {
            // Aggiorna subito la UI: se il server rifiuta si ricarica e si
            // torna al valore vero, ma il toggle non resta bloccato.
            _state.value = UiState.Ready(current.copy(periodMode = mode))
            repository.updateSettings(SettingsPatch(periodMode = mode)).onFailure { load() }
        }
    }

    /**
     * L'ora di inizio della giornata vive sul server, non sul telefono: così
     * vale anche reinstallando l'app o accedendo da un altro dispositivo, ed è
     * la stessa con cui il server calcola i recap.
     */
    fun setDailyRecapHour(hour: Int) {
        val current = (_state.value as? UiState.Ready)?.data ?: return
        viewModelScope.launch {
            _state.value = UiState.Ready(current.copy(dailyRecapHour = hour))
            repository.updateSettings(SettingsPatch(dailyRecapHour = hour)).onFailure { load() }
        }
    }

    /**
     * Importa i file dell'archivio Spotify, uno alla volta.
     *
     * Il file viene inviato tale e quale, senza essere letto in memoria: sono
     * decine di MB, e costruirne l'albero JSON sul telefono per poi
     * riserializzarlo costava parecchie volte la dimensione del file.
     *
     * Il server accoda e risponde subito; l'elaborazione vera dura minuti e la
     * si segue interrogando lo stato del job. Aspettare dentro la richiesta
     * HTTP non funzionava: scadeva prima il client, e comunque un proxy davanti
     * al server chiude le connessioni ferme da troppo tempo.
     */
    fun importFiles(resolver: ContentResolver, uris: List<Uri>, names: List<String>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            var imported = 0
            var duplicate = 0
            val failures = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            var enrichmentRunning = false

            uris.forEachIndexed { index, uri ->
                val name = names.getOrElse(index) { "file-$index.json" }
                val label = if (uris.size > 1) "File ${index + 1} di ${uris.size} · $name" else name

                report(label, "Invio al server…")

                val size = withContext(Dispatchers.IO) { fileSize(resolver, uri) }
                val queued = repository
                    .importStreamingHistory(name, UriRequestBody(resolver, uri, size))
                    .getOrElse { error ->
                        failures += "$name: ${error.message}"
                        return@forEachIndexed
                    }

                val job = awaitJob(queued.jobId) { report(label, describe(it)) }.getOrElse { error ->
                    failures += "$name: ${error.message}"
                    return@forEachIndexed
                }

                if (job.status == "error") {
                    failures += "$name: ${job.error ?: "import non riuscito"}"
                } else {
                    imported += job.rowsImported
                    duplicate += job.rowsDuplicate
                    job.warning?.let { warnings += it }
                }
                enrichmentRunning = job.enrichment?.running == true
            }

            _importProgress.value = ImportProgress(
                running = false,
                message = buildString {
                    append(summary(imported, duplicate))
                    if (enrichmentRunning) {
                        append(" Foto e generi degli artisti arrivano man mano.")
                    }
                    // Distinti dai fallimenti: qui il file è passato, ma solo
                    // in parte. Nasconderlo farebbe credere completo un
                    // archivio che non lo è.
                    warnings.distinct().forEach { append("\n$it") }
                    failures.forEach { append("\n$it") }
                },
            )
            load()
        }
    }

    /**
     * "0 ascolti importati" da solo è fuorviante: è anche il risultato di un
     * file caricato due volte, dove non c'era niente di nuovo da aggiungere e
     * l'import è andato benissimo.
     */
    private fun summary(imported: Int, duplicate: Int): String = when {
        imported == 0 && duplicate > 0 ->
            "Nessun ascolto nuovo: questi $duplicate erano già in archivio."
        imported == 0 -> "Nessun ascolto importato."
        duplicate > 0 -> "$imported ascolti importati, $duplicate erano già in archivio."
        else -> "$imported ascolti importati."
    }

    private fun report(label: String, phase: String) {
        _importProgress.value = ImportProgress(running = true, message = "$label\n$phase")
    }

    /**
     * Attende la fine di un import interrogandone lo stato.
     *
     * Un errore di rete durante l'attesa non significa che l'import sia
     * fallito: gira sul server e prosegue anche a telefono scollegato. Per
     * questo si riprova invece di dichiarare subito il fallimento.
     */
    private suspend fun awaitJob(jobId: String, onProgress: (ImportJob) -> Unit): Result<ImportJob> {
        var networkFailures = 0

        while (true) {
            val result = repository.importJob(jobId)
            val job = result.getOrNull()

            if (job == null) {
                val error = result.exceptionOrNull() ?: Exception("Stato non leggibile")
                networkFailures++
                if (error is SessionExpiredException || networkFailures > MAX_POLL_FAILURES) {
                    return Result.failure(error)
                }
            } else {
                networkFailures = 0
                onProgress(job)
                if (job.finished) return Result.success(job)
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    /** Il testo del passo lo scrive il server: qui si copre solo il caso vuoto. */
    private fun describe(job: ImportJob): String =
        if (job.status == "pending") "In coda"
        else job.phase?.takeIf { it.isNotBlank() } ?: "Elaborazione in corso…"

    /**
     * Serve a dichiarare `Content-Length`. Senza, la richiesta parte in
     * chunked: funziona, ma alcuni proxy la gestiscono peggio.
     */
    private fun fileSize(resolver: ContentResolver, uri: Uri): Long =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index)
            else -1L
        } ?: -1L

    fun dismissImportMessage() {
        _importProgress.value = ImportProgress()
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _loggedOut.value = true
        }
    }

    fun deleteAccount() {
        val current = (_state.value as? UiState.Ready)?.data ?: return
        viewModelScope.launch {
            repository.deleteAccount(current.spotifyUserId)
                .onSuccess { _loggedOut.value = true }
                .onFailure { _state.value = UiState.Error(it.message ?: "Cancellazione non riuscita") }
        }
    }
}
