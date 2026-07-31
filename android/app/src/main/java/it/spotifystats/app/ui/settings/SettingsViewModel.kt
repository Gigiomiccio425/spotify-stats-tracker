package it.spotifystats.app.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.Me
import it.spotifystats.app.data.api.SettingsPatch
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class ImportProgress(
    val running: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(private val repository: StatsRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<Me>>(UiState.Loading)
    val state: StateFlow<UiState<Me>> = _state.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
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
     * Importa i file dell'archivio Spotify, uno per volta.
     *
     * Il parsing avviene sul telefono perché il backend riceve già JSON pronto:
     * i file sono da qualche decina di MB e leggerli in memoria è pesante ma
     * accettabile per un'operazione che si fa una volta sola.
     */
    fun importFiles(resolver: ContentResolver, uris: List<Uri>, names: List<String>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _importProgress.value = ImportProgress(running = true, message = "Lettura dei file…")
            var imported = 0
            var failed = 0

            uris.forEachIndexed { index, uri ->
                val name = names.getOrElse(index) { "file-$index.json" }
                _importProgress.value = ImportProgress(
                    running = true,
                    message = "File ${index + 1} di ${uris.size}: $name",
                )

                val parsed = runCatching {
                    withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { stream ->
                            json.parseToJsonElement(stream.bufferedReader().readText())
                        }
                    }
                }.getOrNull()

                if (parsed == null) {
                    failed++
                    return@forEachIndexed
                }

                repository.importStreamingHistory(name, parsed as JsonElement)
                    .onSuccess { imported += it.rowsImported }
                    .onFailure { failed++ }
            }

            _importProgress.value = ImportProgress(
                running = false,
                message = buildString {
                    append("$imported ascolti importati")
                    if (failed > 0) append(", $failed file non riusciti")
                },
            )
            load()
        }
    }

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
