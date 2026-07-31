package it.spotifystats.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.data.api.HistoryItem
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.Artwork
import it.spotifystats.app.ui.components.EmptyState
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.Refreshable
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

@Composable
fun HistoryScreen(onTrackClick: (String) -> Unit) {
    val viewModel = repositoryViewModel { HistoryViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    Refreshable(isRefreshing = refreshing, onRefresh = viewModel::refresh) {
        when (val current = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(current.message, onRetry = { viewModel.load() })
            is UiState.Ready -> HistoryList(current.data, viewModel::loadMore, onTrackClick)
        }
    }
}

@Composable
private fun HistoryList(
    data: HistoryData,
    onLoadMore: () -> Unit,
    onTrackClick: (String) -> Unit,
) {
    if (data.items.isEmpty()) {
        EmptyState(
            title = "Storico vuoto",
            subtitle = "Gli ascolti compaiono qui entro un quarto d'ora da quando li fai.",
        )
        return
    }

    val listState = rememberLazyListState()

    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 10
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }

    // Raggruppa per giorno locale, con l'intestazione appiccicata in alto
    // mentre si scorre.
    val grouped = remember(data.items) { data.items.groupBy { Format.dayKey(it.playedAt) } }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        grouped.forEach { (_, itemsOfDay) ->
            item {
                DayHeader(Format.relativeDay(itemsOfDay.first().playedAt), itemsOfDay.size)
            }
            items(itemsOfDay.size, key = { itemsOfDay[it].id }) { index ->
                HistoryRow(itemsOfDay[index], onClick = { onTrackClick(itemsOfDay[index].trackId) })
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

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            if (count == 1) "1 ascolto" else "$count ascolti",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
        )
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Format.time(item.playedAt),
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Artwork(item.imageUrl, size = 40)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                item.trackName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.artistNames ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
