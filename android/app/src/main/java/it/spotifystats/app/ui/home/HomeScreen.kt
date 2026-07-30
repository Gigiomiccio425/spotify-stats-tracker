package it.spotifystats.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.Artwork
import it.spotifystats.app.ui.components.EmptyState
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.LabeledBarChart
import it.spotifystats.app.ui.components.ListeningClock
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.RangeSelector
import it.spotifystats.app.ui.components.SectionTitle
import it.spotifystats.app.ui.components.StatTile
import it.spotifystats.app.ui.components.TrackRow
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.Warning

@Composable
fun HomeScreen(
    onTrackClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val viewModel = repositoryViewModel { HomeViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is UiState.Loading -> LoadingState()
        is UiState.Error -> ErrorState(current.message, onRetry = viewModel::load)
        is UiState.Ready -> HomeContent(
            data = current.data,
            onRangeChange = viewModel::setRange,
            onTrackClick = onTrackClick,
            onArtistClick = onArtistClick,
        )
    }
}

@Composable
private fun HomeContent(
    data: HomeData,
    onRangeChange: (String) -> Unit,
    onTrackClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val overview = data.overview

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Ciao${data.me.displayName?.let { ", $it" } ?: ""}",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    "In ascolto da ${Format.daysSince(data.me.trackingSince)} giorni",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        // Se il poller ha trovato la finestra piena, parte dello storico può
        // essere andato perso: dirlo è più utile che mostrare numeri che
        // l'utente crederebbe completi.
        if (data.me.sync.possibleGaps > 0) {
            item { GapWarning(data.me.sync.possibleGaps) }
        }

        item {
            RangeSelector(selected = data.range, onSelect = onRangeChange)
            VerticalSpacer(8)
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    value = Format.minutes(overview.minutesPlayed),
                    label = "minuti",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = Format.number(overview.playCount),
                    label = "ascolti",
                    modifier = Modifier.weight(1f),
                )
            }
            VerticalSpacer(12)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    value = Format.number(overview.distinctArtists),
                    label = "artisti",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = "${overview.streak}",
                    label = if (overview.streak == 1) "giorno di fila" else "giorni di fila",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (overview.playCount == 0) {
            item {
                VerticalSpacer(32)
                EmptyState(
                    title = "Ancora nessun ascolto in archivio",
                    subtitle = "L'archiviazione parte dal momento del collegamento. " +
                        "Ascolta qualcosa su Spotify: comparirà entro un quarto d'ora.",
                )
            }
            return@LazyColumn
        }

        item {
            VerticalSpacer(24)
            SectionTitle("Andamento")
            LabeledBarChart(
                values = data.timeline.map { it.msPlayed.toFloat() },
                startLabel = data.timeline.firstOrNull()?.bucket ?: "",
                endLabel = data.timeline.lastOrNull()?.bucket ?: "",
            )
        }

        item {
            VerticalSpacer(16)
            SectionTitle("Quando ascolti")
            ListeningClock(data.clock.map { it.playCount })
        }

        if (overview.topArtists.isNotEmpty()) {
            item {
                VerticalSpacer(16)
                SectionTitle("I tuoi artisti")
                TopArtistsStrip(data, onArtistClick)
            }
        }

        if (overview.topTracks.isNotEmpty()) {
            item {
                VerticalSpacer(16)
                SectionTitle("I tuoi brani")
            }
            itemsIndexed(overview.topTracks) { index, track ->
                TrackRow(track, position = index + 1, onClick = { onTrackClick(track.id) })
            }
        }

        item { VerticalSpacer(32) }
    }
}

@Composable
private fun TopArtistsStrip(data: HomeData, onArtistClick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        data.overview.topArtists.take(4).forEach { artist ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onArtistClick(artist.id) }
                    .padding(vertical = 4.dp),
            ) {
                Artwork(artist.imageUrl, size = 72, shape = RoundedCornerShape(50))
                VerticalSpacer(6)
                Text(
                    artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${artist.playCount}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
            }
        }
    }
}

@Composable
private fun GapWarning(gaps: Int) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated)
            .padding(12.dp),
    ) {
        Text("Possibili buchi nell'archivio", style = MaterialTheme.typography.titleMedium, color = Warning)
        Text(
            "In $gaps controlli Spotify aveva già superato le 50 tracce di memoria. " +
                "Gli ascolti di quelle finestre non sono recuperabili.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}
