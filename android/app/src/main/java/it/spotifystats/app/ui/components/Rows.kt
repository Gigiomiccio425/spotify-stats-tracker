package it.spotifystats.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.spotifystats.app.data.api.TopAlbum
import it.spotifystats.app.data.api.TopArtist
import it.spotifystats.app.data.api.TopGenre
import it.spotifystats.app.data.api.TopTrack
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

/** Numero di posizione in classifica: monospazio visivo, larghezza fissa. */
@Composable
private fun Rank(position: Int) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
        Text(
            "$position",
            style = MaterialTheme.typography.bodyMedium,
            color = if (position <= 3) Accent else TextTertiary,
            fontWeight = if (position <= 3) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun TrackRow(
    track: TopTrack,
    position: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        position?.let { Rank(it) }
        Artwork(track.imageUrl, size = 48)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                track.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artistNames ?: "Artista sconosciuto",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${Format.number(track.playCount)}×",
                style = MaterialTheme.typography.titleMedium,
                color = Accent,
            )
            Text(
                Format.duration(track.msPlayed),
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
            )
        }
    }
}

@Composable
fun ArtistRow(artist: TopArtist, position: Int? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        position?.let { Rank(it) }
        Artwork(artist.imageUrl, size = 48, shape = RoundedCornerShape(50))
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                artist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.genres.isNotEmpty()) {
                Text(
                    artist.genres.take(2).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${Format.number(artist.playCount)}×",
                style = MaterialTheme.typography.titleMedium,
                color = Accent,
            )
            Text(
                Format.duration(artist.msPlayed),
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
            )
        }
    }
}

@Composable
fun AlbumRow(album: TopAlbum, position: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        position?.let { Rank(it) }
        Artwork(album.imageUrl, size = 48)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                album.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                album.artistNames ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${Format.number(album.playCount)}×",
            style = MaterialTheme.typography.titleMedium,
            color = Accent,
        )
    }
}

/**
 * Riga di genere con barra proporzionale al primo in classifica.
 * Il confronto relativo dice più del numero assoluto: "il doppio del secondo"
 * si coglie a colpo d'occhio, "1.284 ascolti" no.
 */
@Composable
fun GenreRow(genre: TopGenre, position: Int, maxPlayCount: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$position. ${genre.genre.replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${Format.number(genre.playCount)}×",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(SurfaceElevated),
        ) {
            val fraction = if (maxPlayCount > 0) genre.playCount.toFloat() / maxPlayCount else 0f
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Accent),
            )
        }
    }
}

/** Riquadro con un numero grande e la sua etichetta. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Accent)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

@Composable
fun MiniTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
fun ArtworkStack(urls: List<String?>, size: Int = 56) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        urls.take(4).forEach { Artwork(it, size = size) }
    }
}

@Composable
fun Dot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(TextTertiary),
    )
}
