package it.spotifystats.app.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.spotifystats.app.data.api.Recap
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.components.Artwork
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

/**
 * La card condivisibile. È anche ciò che si vede in anteprima: una sola
 * definizione, così l'immagine esportata non può divergere da ciò che
 * l'utente ha approvato.
 *
 * Vincoli di brand Spotify rispettati qui:
 *  - le copertine non vengono ritagliate, deformate né coperte da testo;
 *  - in fondo compare l'attribuzione a Spotify.
 */
@Composable
fun ShareCard(recap: Recap, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1F1F1F), Background, Color(0xFF0A1F12)),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(recap)
            Totals(recap)
            TopFive("Brani", recap.topTracks.map { it.name to it.artistNames }, recap.topTracks.map { it.imageUrl })
            TopFive("Artisti", recap.topArtists.map { it.name to null }, recap.topArtists.map { it.imageUrl })
            Box(Modifier.weight(1f))
            Footer(recap)
        }
    }
}

@Composable
private fun Header(recap: Recap) {
    Column {
        Text(
            when (recap.period.type) {
                "week" -> "LA MIA SETTIMANA"
                "month" -> "IL MIO MESE"
                else -> "IL MIO ANNO"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
        )
        Text(
            recap.period.label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (recap.period.partial) {
            // Un periodo coperto solo in parte non è confrontabile con gli
            // altri: dichiararlo sulla card evita un confronto ingannevole.
            Text(
                "periodo parziale",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun Totals(recap: Recap) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CardStat(Format.minutes(recap.totals.minutesPlayed), "minuti")
        CardStat(Format.number(recap.totals.playCount), "ascolti")
        CardStat(Format.number(recap.totals.distinctArtists), "artisti")
    }
    recap.minutesChangePct?.let { pct ->
        Text(
            if (pct >= 0) "+$pct% rispetto al periodo precedente" else "$pct% rispetto al periodo precedente",
            style = MaterialTheme.typography.labelSmall,
            color = if (pct >= 0) Accent else TextSecondary,
        )
    }
}

@Composable
private fun CardStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Accent)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun TopFive(title: String, entries: List<Pair<String, String?>>, images: List<String?>) {
    if (entries.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        entries.take(5).forEachIndexed { index, (name, subtitle) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Artwork(
                    images.getOrNull(index),
                    size = 28,
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)),
                )
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer(recap: Recap) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        recap.busiestDay?.let {
            Text(
                "Giorno più intenso: ${it.day} · ${it.playCount} ascolti",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Text(
            "Dati di ascolto da Spotify",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
        )
    }
}
