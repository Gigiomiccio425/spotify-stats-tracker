package it.spotifystats.app.ui.top

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.AlbumRow
import it.spotifystats.app.ui.components.ArtistRow
import it.spotifystats.app.ui.components.EmptyState
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.GenreRow
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.MusicalAgePanel
import it.spotifystats.app.ui.components.RangeSelector
import it.spotifystats.app.ui.components.Refreshable
import it.spotifystats.app.ui.components.TrackRow
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.TextSecondary

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
    val currentTab = (state as? UiState.Ready)?.data?.tab ?: TopTab.Tracks
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
    onTrackClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    val isEmpty = when (data.tab) {
        TopTab.Tracks -> data.tracks.isEmpty()
        TopTab.Artists -> data.artists.isEmpty()
        TopTab.Albums -> data.albums.isEmpty()
        TopTab.Genres -> data.genres.isEmpty()
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
                val max = data.genres.firstOrNull()?.playCount ?: 0
                itemsIndexed(data.genres, key = { _, g -> g.genre }) { index, genre ->
                    GenreRow(genre, position = index + 1, maxPlayCount = max)
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
