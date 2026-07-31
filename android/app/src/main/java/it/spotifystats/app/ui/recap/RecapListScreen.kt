package it.spotifystats.app.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.data.api.PeriodRef
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.EmptyState
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.SectionTitle
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

/** Filtro per tipo di periodo. `null` mostra tutto. */
private data class RecapFilter(val type: String?, val label: String)

private val RecapFilters = listOf(
    RecapFilter(null, "Tutti"),
    RecapFilter("day", "Giornalieri"),
    RecapFilter("week", "Settimanali"),
    RecapFilter("month", "Mensili"),
    RecapFilter("year", "Annuali"),
)

@Composable
fun RecapListScreen(onOpenRecap: (type: String, key: String) -> Unit) {
    val viewModel = repositoryViewModel { RecapListViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Il filtro è locale: il server manda comunque tutti i gruppi in una sola
    // risposta, quindi cambiarlo non costa una nuova richiesta.
    var filter by remember { mutableStateOf<String?>(null) }

    when (val current = state) {
        is UiState.Loading -> LoadingState()
        is UiState.Error -> ErrorState(current.message, onRetry = viewModel::load)
        is UiState.Ready -> {
            val data = current.data
            val visible = data.groups.filter { filter == null || it.type == filter }
            val hasAny = visible.any { it.periods.isNotEmpty() }

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Recap", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            // La data di partenza è quella del primo ascolto in
                            // archivio, non del collegamento: con l'archivio
                            // Spotify importato può essere di anni prima.
                            "Dal ${Format.date(data.archive.firstPlayAt ?: data.trackingSince)} " +
                                "· modalità " +
                                if (data.mode == "anniversary") "anniversario" else "calendario",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        if (data.archive.importedPlays > 0) {
                            Text(
                                "Include ${Format.number(data.archive.importedPlays)} ascolti " +
                                    "dall'archivio Spotify che hai caricato.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                item {
                    FilterRow(filter) { filter = it }
                    VerticalSpacer(8)
                }

                if (!hasAny) {
                    item {
                        EmptyState(
                            title = "Nessun periodo ancora concluso",
                            subtitle = "Un recap si genera quando il periodo è finito e contiene " +
                                "almeno un ascolto. Il primo arriva domani, con il riepilogo di " +
                                "oggi. Caricando l'archivio Spotify da Profilo compaiono anche " +
                                "quelli degli anni passati.",
                        )
                    }
                }

                visible.forEach { group ->
                    if (group.periods.isEmpty()) return@forEach
                    // L'intestazione si mostra solo quando c'è più di un tipo a
                    // schermo: con il filtro attivo ripeterebbe l'ovvio.
                    if (filter == null) item { SectionTitle(titleFor(group.type)) }
                    items(group.periods.size) { index ->
                        PeriodCard(group.periods[index]) {
                            onOpenRecap(group.type, group.periods[index].key)
                        }
                    }
                    item { VerticalSpacer(8) }
                }

                item {
                    VerticalSpacer(16)
                    Text(
                        "I recap sono calcolati dal server sull'archivio: restano disponibili " +
                            "anche reinstallando l'app o accedendo da un altro dispositivo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    VerticalSpacer(32)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(RecapFilters.size) { index ->
            val option = RecapFilters[index]
            FilterChip(
                selected = option.type == selected,
                onClick = { onSelect(option.type) },
                label = { Text(option.label, style = MaterialTheme.typography.labelLarge) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceElevated,
                    labelColor = TextSecondary,
                    selectedContainerColor = Accent,
                    selectedLabelColor = Color.Black,
                ),
                border = null,
            )
        }
    }
}

private fun titleFor(type: String): String = when (type) {
    "day" -> "Giorni"
    "week" -> "Settimane"
    "month" -> "Mesi"
    else -> "Anni"
}

@Composable
private fun PeriodCard(period: PeriodRef, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                period.label.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (period.partial) {
                Text(
                    "periodo parziale",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Accent)
    }
}
