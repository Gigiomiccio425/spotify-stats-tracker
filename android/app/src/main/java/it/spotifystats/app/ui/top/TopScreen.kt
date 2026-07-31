package it.spotifystats.app.ui.top

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.AlbumRow
import it.spotifystats.app.ui.components.ArtistRow
import it.spotifystats.app.ui.components.EmptyState
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.GenreRow
import it.spotifystats.app.ui.components.LabeledBarChart
import it.spotifystats.app.ui.components.ListeningClock
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.MusicalAgePanel
import it.spotifystats.app.ui.components.RangeSelector
import it.spotifystats.app.ui.components.Refreshable
import it.spotifystats.app.ui.components.SectionTitle
import it.spotifystats.app.ui.components.StatTile
import it.spotifystats.app.ui.components.TrackRow
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.components.WeekdayChart
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

@Composable
fun TopScreen(
    onTrackClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val viewModel = repositoryViewModel { TopViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    // La scheda e il selettore di periodo restano visibili anche durante il
    // caricamento: sparire e ricomparire a ogni cambio è disorientante.
    val currentTab = (state as? UiState.Ready)?.data?.tab ?: TopTab.Trends
    val currentRange = (state as? UiState.Ready)?.data?.range ?: "lifetime"

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = currentTab.ordinal,
            containerColor = Background,
            contentColor = Accent,
            edgePadding = 16.dp,
        ) {
            TopTab.entries.forEach { tab ->
                Tab(
                    selected = tab == currentTab,
                    onClick = { viewModel.setTab(tab) },
                    text = {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (tab == currentTab) Accent else TextSecondary,
                        )
                    },
                )
            }
        }

        VerticalSpacer(8)
        RangeSelector(selected = currentRange, onSelect = viewModel::setRange)
        VerticalSpacer(8)

        Refreshable(isRefreshing = refreshing, onRefresh = viewModel::refresh) {
            when (val current = state) {
                is UiState.Loading -> LoadingState()
                is UiState.Error -> ErrorState(current.message, onRetry = { viewModel.load() })
                is UiState.Ready -> TopList(
                    data = current.data,
                    onLoadMore = viewModel::loadMore,
                    onBucketChange = viewModel::setBucket,
                    onTrackClick = onTrackClick,
                    onArtistClick = onArtistClick,
                )
            }
        }
    }
}

@Composable
private fun TopList(
    data: TopData,
    onLoadMore: () -> Unit,
    onBucketChange: (String) -> Unit,
    onTrackClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    val isEmpty = when (data.tab) {
        TopTab.Trends -> data.trends == null
        TopTab.Tracks -> data.tracks.isEmpty()
        TopTab.Artists -> data.artists.isEmpty()
        TopTab.Albums -> data.albums.isEmpty()
        // I generi hanno un vuoto tutto loro, spiegato più sotto.
        TopTab.Genres -> false
        TopTab.Years -> data.releaseYears == null
    }

    if (isEmpty) {
        EmptyState(
            title = "Niente in questo periodo",
            subtitle = "Prova a estendere l'intervallo, oppure aspetta che l'archivio si riempia.",
        )
        return
    }

    // Precarica la pagina successiva prima di toccare il fondo: lo scorrimento
    // non si interrompe mai.
    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 8
        }
    }

    LaunchedEffect(listState, data.tab, data.range) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        when (data.tab) {
            TopTab.Trends -> item {
                data.trends?.let { TrendsPanel(it, onBucketChange) }
            }
            TopTab.Tracks -> itemsIndexed(data.tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, position = index + 1, onClick = { onTrackClick(track.id) })
            }
            TopTab.Artists -> itemsIndexed(data.artists, key = { _, a -> a.id }) { index, artist ->
                ArtistRow(artist, position = index + 1, onClick = { onArtistClick(artist.id) })
            }
            TopTab.Albums -> itemsIndexed(data.albums, key = { _, a -> a.id }) { index, album ->
                AlbumRow(album, position = index + 1)
            }
            TopTab.Genres -> {
                if (data.genres.isEmpty()) {
                    item { GenresUnavailable(data) }
                } else {
                    val max = data.genres.firstOrNull()?.playCount ?: 0
                    itemsIndexed(data.genres, key = { _, g -> g.genre }) { index, genre ->
                        GenreRow(genre, position = index + 1, maxPlayCount = max)
                    }
                    item { GenreCoverageNote(data) }
                }
            }
            TopTab.Years -> item {
                data.releaseYears?.let { MusicalAgePanel(it) }
            }
        }

        if (data.loadingMore) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Accent)
                }
            }
        }

        item { VerticalSpacer(32) }
    }
}

/** Panoramica dell'ascolto: totali, andamento, giorni della settimana, orologio. */
@Composable
private fun TrendsPanel(trends: TrendsData, onBucketChange: (String) -> Unit) {
    val overview = trends.overview

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile(
                value = Format.minutes(overview.minutesPlayed),
                label = "minuti",
                hint = "${overview.minutesPlayed / 60} ore",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = Format.number(overview.playCount),
                label = "ascolti",
                hint = "${Format.number(overview.distinctTracks)} brani diversi",
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
                value = "${overview.listeningDays}",
                label = if (overview.listeningDays == 1) "giorno con ascolti" else "giorni con ascolti",
                hint = if (overview.streak > 0) "serie di ${overview.streak}" else null,
                modifier = Modifier.weight(1f),
            )
        }

        VerticalSpacer(24)
        SectionTitle("Andamento")
        BucketSelector(trends.bucket, onBucketChange)
        VerticalSpacer(8)
        LabeledBarChart(
            values = trends.timeline.map { it.msPlayed.toFloat() },
            startLabel = trends.timeline.firstOrNull()?.bucket ?: "",
            endLabel = trends.timeline.lastOrNull()?.bucket ?: "",
            heightDp = 140,
        )

        VerticalSpacer(24)
        SectionTitle("Giorni della settimana")
        WeekdayChart(trends.weekdays.map { it.playCount })

        VerticalSpacer(24)
        SectionTitle("Quando ascolti")
        ListeningClock(trends.clock.map { it.playCount })
        trends.clock.maxByOrNull { it.playCount }?.takeIf { it.playCount > 0 }?.let { peak ->
            Text(
                "Ora di punta: le ${peak.hour}:00, con ${Format.number(peak.playCount)} ascolti.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        VerticalSpacer(16)
        Text(
            "I minuti raccolti dal poller sono stimati con la durata del brano: " +
                "Spotify non dice quanto hai ascoltato davvero.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BucketSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("day" to "Giorno", "week" to "Settimana", "month" to "Mese")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
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

/**
 * Una classifica dei generi vuota ha due cause opposte, e senza distinguerle
 * l'utente pensa che l'app sia rotta.
 */
@Composable
private fun GenresUnavailable(data: TopData) {
    EmptyState(
        title = if (data.artistsTotal == 0) "Ancora nessun ascolto" else "Nessun genere disponibile",
        subtitle = if (data.artistsTotal == 0) {
            "Prova a estendere l'intervallo, oppure aspetta che l'archivio si riempia."
        } else {
            "Spotify non attribuisce generi a nessuno dei ${data.artistsTotal} artisti ascoltati " +
                "in questo periodo. I generi non esistono per traccia: vengono dall'artista, e " +
                "per molti Spotify non ne dichiara. Il server ricontrolla ogni settimana."
        },
    )
}

@Composable
private fun GenreCoverageNote(data: TopData) {
    if (data.artistsTotal == 0 || data.artistsWithGenres >= data.artistsTotal) return

    Text(
        "Calcolato su ${data.artistsWithGenres} dei ${data.artistsTotal} artisti ascoltati: " +
            "per gli altri Spotify non dichiara alcun genere.",
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
