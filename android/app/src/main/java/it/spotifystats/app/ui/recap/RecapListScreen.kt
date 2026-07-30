package it.spotifystats.app.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.data.api.PeriodRef
import it.spotifystats.app.data.api.RecapGroup
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

@Composable
fun RecapListScreen(onOpenRecap: (type: String, key: String) -> Unit) {
    val viewModel = repositoryViewModel { RecapListViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is UiState.Loading -> LoadingState()
        is UiState.Error -> ErrorState(current.message, onRetry = viewModel::load)
        is UiState.Ready -> {
            val data = current.data
            val hasAny = data.groups.any { it.periods.isNotEmpty() }

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Recap", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Dal ${Format.date(data.trackingSince)} · modalità " +
                                if (data.mode == "anniversary") "anniversario" else "calendario",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }

                if (!hasAny) {
                    item {
                        EmptyState(
                            title = "Nessun periodo ancora concluso",
                            subtitle = "Un recap si genera quando la settimana, il mese o l'anno " +
                                "sono finiti. Il primo arriverà a fine settimana.",
                        )
                    }
                }

                data.groups.forEach { group ->
                    if (group.periods.isEmpty()) return@forEach
                    item { SectionTitle(titleFor(group)) }
                    items(group.periods.size) { index ->
                        PeriodCard(group.periods[index]) { onOpenRecap(group.type, group.periods[index].key) }
                    }
                    item { VerticalSpacer(8) }
                }

                item { VerticalSpacer(32) }
            }
        }
    }
}

private fun titleFor(group: RecapGroup): String = when (group.type) {
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
