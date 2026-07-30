package it.spotifystats.app.ui.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.data.api.Recap
import it.spotifystats.app.share.ShareCardRenderer
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.UiState
import it.spotifystats.app.ui.components.ErrorState
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.components.MiniTile
import it.spotifystats.app.ui.components.SectionTitle
import it.spotifystats.app.ui.components.TrackRow
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.repositoryViewModel
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapDetailScreen(
    type: String,
    periodKey: String,
    onBack: () -> Unit,
) {
    val viewModel = repositoryViewModel(key = "$type/$periodKey") {
        RecapDetailViewModel(it, type, periodKey)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Recap") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
    ) { padding ->
        when (val current = state) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))
            is UiState.Error -> ErrorState(current.message, viewModel::load, Modifier.padding(padding))
            is UiState.Ready -> RecapContent(current.data, Modifier.padding(padding))
        }
    }
}

@Composable
private fun RecapContent(recap: Recap, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Registra ciò che viene disegnato: l'immagine condivisa è esattamente la
    // card che l'utente sta guardando, non una seconda versione da tenere
    // allineata a mano.
    val graphicsLayer = rememberGraphicsLayer()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .drawWithContent {
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                },
        ) {
            ShareCard(recap)
        }

        Button(
            onClick = {
                scope.launch {
                    val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
                    val file = ShareCardRenderer.writePng(
                        context = context,
                        bitmap = bitmap,
                        filename = "recap-${recap.period.type}-${recap.period.key}.png",
                    )
                    ShareCardRenderer.share(
                        context = context,
                        file = file,
                        text = "${recap.period.label}: ${Format.minutes(recap.totals.minutesPlayed)} minuti di musica.",
                    )
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text("  Condividi", style = MaterialTheme.typography.labelLarge)
        }

        VerticalSpacer(24)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MiniTile(Format.minutes(recap.totals.minutesPlayed), "minuti")
            MiniTile(Format.number(recap.totals.playCount), "ascolti")
            MiniTile(Format.number(recap.totals.distinctTracks), "brani")
            MiniTile("${recap.totals.listeningDays}", "giorni")
        }

        if (recap.topTracks.isNotEmpty()) {
            VerticalSpacer(16)
            SectionTitle("Brani più ascoltati")
            recap.topTracks.forEachIndexed { index, track ->
                TrackRow(track, position = index + 1)
            }
        }

        if (recap.topGenres.isNotEmpty()) {
            VerticalSpacer(16)
            SectionTitle("Generi")
            Text(
                recap.topGenres.joinToString(" · ") { it.genre },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        VerticalSpacer(48)
    }
}
