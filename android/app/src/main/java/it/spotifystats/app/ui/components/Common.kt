package it.spotifystats.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

/**
 * Copertina o foto artista.
 *
 * Le immagini di Spotify non vanno mai ritagliate o deformate: [ContentScale.Crop]
 * su un contenitore quadrato mantiene le proporzioni. Il raggio è 4dp per gli
 * album e pieno per gli artisti, come nell'app originale.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(SurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size((size / 2.5).dp),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        action?.invoke()
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent)
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Riprova", color = Accent) }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Un intervallo temporale selezionabile, con l'etichetta mostrata all'utente. */
data class RangeOption(val value: String, val label: String)

/**
 * Niente voce "Dall'inizio": ora che il poller allinea la data di inizio al
 * più vecchio ascolto archiviato, dava gli stessi numeri di "Tutto" e faceva
 * solo dubitare di quale delle due fosse quella giusta.
 */
val DefaultRanges = listOf(
    RangeOption("week", "Settimana"),
    RangeOption("month", "Mese"),
    RangeOption("4weeks", "4 settimane"),
    RangeOption("6months", "6 mesi"),
    RangeOption("year", "Anno"),
    // Include anche gli ascolti importati dall'archivio Spotify, precedenti al
    // collegamento dell'account.
    RangeOption("lifetime", "Tutto"),
)

@Composable
fun RangeSelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    options: List<RangeOption> = DefaultRanges,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { index ->
            val option = options[index]
            FilterChip(
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
                label = { Text(option.label, style = MaterialTheme.typography.labelLarge) },
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
fun VerticalSpacer(height: Int) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(height.dp))
}
