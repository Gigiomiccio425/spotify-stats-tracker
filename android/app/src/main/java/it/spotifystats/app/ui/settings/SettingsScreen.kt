package it.spotifystats.app.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.StatsApplication
import it.spotifystats.app.data.api.Me
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.Artwork
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.SectionTitle
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Danger
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary
import it.spotifystats.app.ui.theme.Warning

@Composable
fun SettingsScreen(onLoggedOut: () -> Unit, onChangeServer: () -> Unit) {
    val viewModel = repositoryViewModel { SettingsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()

    val app = LocalContext.current.applicationContext as StatsApplication
    val serverUrl by app.server.url.collectAsState(initial = app.server.currentUrl)

    if (loggedOut) {
        onLoggedOut()
        return
    }

    when (val current = state) {
        is UiState.Loading -> LoadingState()
        // Anche in errore la sezione del server resta raggiungibile: se
        // l'indirizzo è sbagliato, /me fallisce, e nascondere il posto in cui
        // correggerlo lascerebbe l'utente bloccato.
        is UiState.Error -> Column(Modifier.fillMaxSize()) {
            ServerSection(serverUrl, onChangeServer)
            HorizontalDivider(color = SurfaceElevated)
            ErrorState(current.message, onRetry = viewModel::load)
        }
        is UiState.Ready -> SettingsContent(
            me = current.data,
            viewModel = viewModel,
            importProgress = importProgress,
            serverUrl = serverUrl,
            onChangeServer = onChangeServer,
        )
    }
}

@Composable
private fun ServerSection(serverUrl: String?, onChangeServer: () -> Unit) {
    SectionTitle("Server")
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            serverUrl ?: "non configurato",
            style = MaterialTheme.typography.bodyLarge,
            color = if (serverUrl == null) Danger else TextSecondary,
        )
        TextButton(onClick = onChangeServer, contentPadding = PaddingValues(0.dp)) {
            Text("Cambia server", color = Accent)
        }
        Text(
            "Cambiando server si esce dall'account: la sessione vale solo per il backend " +
                "che l'ha rilasciata.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
        )
    }
}

@Composable
private fun SettingsContent(
    me: Me,
    viewModel: SettingsViewModel,
    importProgress: ImportProgress,
    serverUrl: String?,
    onChangeServer: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as StatsApplication
    var confirmDelete by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val names = uris.map { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else "history.json"
            } ?: "history.json"
        }
        viewModel.importFiles(context.contentResolver, uris, names)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(me.imageUrl, size = 64, shape = RoundedCornerShape(50))
            Column(Modifier.padding(start = 16.dp)) {
                Text(me.displayName ?: me.spotifyUserId, style = MaterialTheme.typography.titleLarge)
                Text(
                    "In archivio dal ${Format.date(me.trackingSince)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        HorizontalDivider(color = SurfaceElevated)
        ServerSection(serverUrl, onChangeServer)

        HorizontalDivider(color = SurfaceElevated)
        SectionTitle("Collegamento a Spotify")

        Column(Modifier.padding(horizontal = 16.dp)) {
            if (me.spotify.linked) {
                Text("Attivo", style = MaterialTheme.typography.bodyLarge, color = Accent)
                Text(
                    "Il server interroga Spotify per tuo conto ogni 15 minuti.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            } else {
                Text("Interrotto", style = MaterialTheme.typography.bodyLarge, color = Danger)
                Text(
                    me.spotify.invalidReason
                        ?: "Il server non riesce più a interrogare Spotify per tuo conto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Text(
                    "Nessun nuovo ascolto viene archiviato finché non ricolleghi. " +
                        "Quelli già in archivio restano.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            TextButton(
                onClick = { app.auth.startLogin(context) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    if (me.spotify.linked) "Ricollega comunque" else "Ricollega account Spotify",
                    color = Accent,
                )
            }
        }

        HorizontalDivider(color = SurfaceElevated)
        SectionTitle("Stato archiviazione")
        InfoRow("Ultimo controllo", Format.date(me.sync.lastRunAt).takeIf { me.sync.lastRunAt != null } ?: "mai")
        InfoRow("Esito", me.sync.status ?: "—")
        me.sync.error?.let { InfoRow("Errore", it, valueColor = Danger) }

        if (me.sync.possibleGaps > 0) {
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceElevated)
                    .padding(12.dp),
            ) {
                Text("${me.sync.possibleGaps} possibili buchi", color = Warning, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Il controllo ha trovato piene tutte e 50 le posizioni di memoria di Spotify: " +
                        "significa che fra un controllo e l'altro è passato troppo tempo. " +
                        "Verifica che il cron del backend giri ogni 15 minuti.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        HorizontalDivider(color = SurfaceElevated)
        SectionTitle("Periodi dei recap")

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ancorati alla registrazione", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (me.periodMode == "anniversary") {
                        "Settimane, mesi e anni partono dal giorno in cui hai collegato l'account."
                    } else {
                        "Settimana lunedì-domenica, mese e anno solari."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Switch(
                checked = me.periodMode == "anniversary",
                onCheckedChange = { viewModel.setPeriodMode(if (it) "anniversary" else "calendar") },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }

        HorizontalDivider(color = SurfaceElevated)
        SectionTitle("Inizio della giornata")

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "A che ora comincia una giornata nei recap giornalieri. Con le 4, gli ascolti " +
                    "delle due di notte finiscono nel riepilogo della sera prima.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VerticalSpacer(12)
            HourSelector(me.dailyRecapHour) { viewModel.setDailyRecapHour(it) }
            VerticalSpacer(8)
            Text(
                "La giornata di oggi va dalle ${"%02d".format(me.dailyRecapHour)}:00 di oggi " +
                    "alle ${"%02d".format(me.dailyRecapHour)}:00 di domani.",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }

        HorizontalDivider(color = SurfaceElevated)
        SectionTitle("Storico precedente")

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Spotify consegna su richiesta l'archivio completo dei tuoi ascolti " +
                    "(Extended Streaming History, circa 30 giorni di attesa). Quando arriva, " +
                    "carica qui i file Streaming_History_Audio_*.json.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VerticalSpacer(6)
            Text(
                "A caricamento riuscito compaiono anche i recap dei periodi passati, fino al " +
                    "primo ascolto dell'archivio: settimane, mesi e anni di prima che collegassi " +
                    "l'account.",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            VerticalSpacer(8)
            TextButton(
                onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                enabled = !importProgress.running,
            ) {
                Text(
                    if (importProgress.running) "Importazione in corso…" else "Scegli i file",
                    color = Accent,
                )
            }
            importProgress.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
            }
        }

        HorizontalDivider(color = SurfaceElevated)
        SectionTitle("Account")

        TextButton(onClick = viewModel::logout, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Esci", color = TextSecondary)
        }
        TextButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("Elimina account e tutti i dati", color = Danger)
        }

        VerticalSpacer(48)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare tutto?") },
            text = {
                Text(
                    "Vengono cancellati l'account e ogni ascolto archiviato. " +
                        "L'operazione è definitiva: nulla di questo storico è recuperabile da Spotify.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAccount()
                }) { Text("Elimina", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annulla") }
            },
            containerColor = SurfaceElevated,
        )
    }
}

/**
 * Ventiquattro pulsanti in una riga scorrevole invece di un selettore d'orario:
 * i minuti qui non servono, e scegliere fra 24 valori con un tocco è più rapido
 * che aprire un dialogo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HourSelector(selected: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()

    // Porta in vista l'ora attiva: con 24 voci quella scelta può essere fuori
    // schermo, e sembrerebbe che non ce ne sia nessuna.
    LaunchedEffect(selected) {
        listState.animateScrollToItem((selected - 2).coerceAtLeast(0))
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(24) { hour ->
            FilterChip(
                selected = hour == selected,
                onClick = { onSelect(hour) },
                label = {
                    Text("%02d:00".format(hour), style = MaterialTheme.typography.labelLarge)
                },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceElevated,
                    labelColor = TextSecondary,
                    selectedContainerColor = Accent,
                    selectedLabelColor = androidx.compose.ui.graphics.Color.Black,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = TextSecondary) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
