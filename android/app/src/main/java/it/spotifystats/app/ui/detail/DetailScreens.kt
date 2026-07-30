package it.spotifystats.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.Artwork
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.MiniTile
import it.spotifystats.app.ui.components.SectionTitle
import it.spotifystats.app.ui.components.TrackRow
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
fun TrackDetailScreen(trackId: String, onBack: () -> Unit) {
    val viewModel = repositoryViewModel(key = "track-$trackId") { TrackDetailViewModel(it, trackId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DetailScaffold(onBack) { modifier ->
        when (val current = state) {
            is UiState.Loading -> LoadingState(modifier)
            is UiState.Error -> ErrorState(current.message, viewModel::load, modifier)
            is UiState.Ready -> {
                val track = current.data
                Column(
                    modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(track.imageUrl, size = 200, shape = RoundedCornerShape(8.dp))
                    VerticalSpacer(16)
                    Text(
                        track.name,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text(
                        track.artistNames ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                    track.albumName?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }

                    VerticalSpacer(24)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MiniTile("${track.playCount}", "ascolti")
                        MiniTile(Format.duration(track.msPlayed), "tempo")
                        MiniTile(Format.trackLength(track.durationMs), "durata")
                    }

                    VerticalSpacer(24)
                    InfoLine("Primo ascolto", Format.date(track.firstPlayedAt))
                    InfoLine("Ultimo ascolto", Format.date(track.lastPlayedAt))
                    VerticalSpacer(48)
                }
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(artistId: String, onBack: () -> Unit, onTrackClick: (String) -> Unit) {
    val viewModel = repositoryViewModel(key = "artist-$artistId") { ArtistDetailViewModel(it, artistId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DetailScaffold(onBack) { modifier ->
        when (val current = state) {
            is UiState.Loading -> LoadingState(modifier)
            is UiState.Error -> ErrorState(current.message, viewModel::load, modifier)
            is UiState.Ready -> {
                val artist = current.data
                Column(
                    modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Artwork(artist.imageUrl, size = 160, shape = RoundedCornerShape(50))
                        VerticalSpacer(16)
                        Text(artist.name, style = MaterialTheme.typography.headlineMedium)
                        if (artist.genres.isNotEmpty()) {
                            Text(
                                artist.genres.take(3).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                    }

                    VerticalSpacer(24)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MiniTile("${artist.playCount}", "ascolti")
                        MiniTile(Format.duration(artist.msPlayed), "tempo")
                        MiniTile("${artist.distinctTracks}", "brani diversi")
                    }

                    VerticalSpacer(16)
                    InfoLine("Primo ascolto", Format.date(artist.firstPlayedAt))
                    InfoLine("Ultimo ascolto", Format.date(artist.lastPlayedAt))

                    if (artist.topTracks.isNotEmpty()) {
                        VerticalSpacer(16)
                        SectionTitle("I brani che ascolti di più")
                        artist.topTracks.forEachIndexed { index, track ->
                            TrackRow(track, position = index + 1, onClick = { onTrackClick(track.id) })
                        }
                    }

                    VerticalSpacer(48)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
