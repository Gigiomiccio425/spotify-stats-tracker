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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.spotifystats.app.data.api.Recap
import it.spotifystats.app.ui.Format

/**
 * La card condivisibile. È anche ciò che si vede in anteprima: una sola
 * definizione, così l'immagine esportata non può divergere da ciò che
 * l'utente ha approvato.
 *
 * Vincoli di brand Spotify rispettati qui:
 *  - le copertine non vengono ritagliate, deformate né coperte da testo, e non
 *    cambiano colore al variare del tema;
 *  - in fondo compare l'attribuzione a Spotify.
 */
@Composable
fun ShareCard(
    recap: Recap,
    style: CardStyle = DefaultCardStyle,
    format: CardFormat = CardFormat.Story,
    modifier: Modifier = Modifier,
) {
    // Nel formato quadrato c'è circa metà dello spazio verticale: le classifiche
    // si accorciano invece di traboccare fuori dall'immagine.
    val listSize = if (format == CardFormat.Story) 5 else 3
    val padding = if (format == CardFormat.Story) 24.dp else 20.dp

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(format.ratio)
            .background(Brush.verticalGradient(style.background)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(if (format == CardFormat.Story) 14.dp else 10.dp),
        ) {
            Header(recap, style)
            Totals(recap, style)

            if (format == CardFormat.Story) {
                MusicalAgeLine(recap, style)
            }

            TopFive(
                title = "Brani",
                entries = recap.topTracks.map { it.name to it.artistNames },
                images = recap.topTracks.map { it.imageUrl },
                style = style,
                count = listSize,
            )
            TopFive(
                title = "Artisti",
                entries = recap.topArtists.map { it.name to null },
                images = recap.topArtists.map { it.imageUrl },
                style = style,
                count = listSize,
            )

            Box(Modifier.weight(1f))
            Footer(recap, style, format)
        }
    }
}

@Composable
private fun Header(recap: Recap, style: CardStyle) {
    Column {
        Text(
            when (recap.period.type) {
                "day" -> "LA MIA GIORNATA"
                "week" -> "LA MIA SETTIMANA"
                "month" -> "IL MIO MESE"
                else -> "IL MIO ANNO"
            },
            style = MaterialTheme.typography.labelSmall,
            color = style.accent,
        )
        Text(
            recap.period.label.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = style.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (recap.period.partial) {
            // Un periodo coperto solo in parte non è confrontabile con gli
            // altri: dichiararlo evita un confronto ingannevole.
            Text(
                "periodo parziale",
                style = MaterialTheme.typography.labelSmall,
                color = style.textTertiary,
            )
        }
    }
}

@Composable
private fun Totals(recap: Recap, style: CardStyle) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CardStat(Format.minutes(recap.totals.minutesPlayed), "minuti", style)
        CardStat(Format.number(recap.totals.playCount), "ascolti", style)
        CardStat(Format.number(recap.totals.distinctArtists), "artisti", style)
    }
    recap.minutesChangePct?.let { pct ->
        Text(
            if (pct >= 0) "+$pct% rispetto al periodo precedente"
            else "$pct% rispetto al periodo precedente",
            style = MaterialTheme.typography.labelSmall,
            color = if (pct >= 0) style.accent else style.textSecondary,
        )
    }
}

/** Presente solo dove il server la calcola: mese e anno. */
@Composable
private fun MusicalAgeLine(recap: Recap, style: CardStyle) {
    val stats = recap.releaseYears ?: return
    val average = stats.averageYear ?: return
    val topDecade = stats.decades.maxByOrNull { it.playCount } ?: return

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(style.artworkBackdrop)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                "ETÀ MUSICALE",
                style = MaterialTheme.typography.labelSmall,
                color = style.textTertiary,
            )
            Text(
                "$average",
                style = MaterialTheme.typography.titleLarge,
                color = style.accent,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "DECENNIO PREFERITO",
                style = MaterialTheme.typography.labelSmall,
                color = style.textTertiary,
            )
            Text(
                "anni ${topDecade.decade.toString().takeLast(2)} · ${topDecade.share}%",
                style = MaterialTheme.typography.titleLarge,
                color = style.textPrimary,
            )
        }
    }
}

@Composable
private fun CardStat(value: String, label: String, style: CardStyle) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = style.accent)
        Text(label, style = MaterialTheme.typography.labelSmall, color = style.textSecondary)
    }
}

@Composable
private fun TopFive(
    title: String,
    entries: List<Pair<String, String?>>,
    images: List<String?>,
    style: CardStyle,
    count: Int,
) {
    if (entries.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = style.textTertiary,
        )
        entries.take(count).forEachIndexed { index, (name, subtitle) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = style.textTertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                CardArtwork(images.getOrNull(index), style)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = style.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = style.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Copertina in dimensione fissa. Non usa il componente condiviso perché quello
 * porta con sé i colori del tema dell'app, mentre qui lo sfondo dipende dallo
 * stile scelto per la card.
 */
@Composable
private fun CardArtwork(url: String?, style: CardStyle) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(style.artworkBackdrop),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Footer(recap: Recap, style: CardStyle, format: CardFormat) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (format == CardFormat.Story) {
            recap.busiestDay?.let {
                Text(
                    "Giorno più intenso: ${it.day} · ${it.playCount} ascolti",
                    style = MaterialTheme.typography.labelSmall,
                    color = style.textSecondary,
                )
            }
        }
        Text(
            "Dati di ascolto da Spotify",
            style = MaterialTheme.typography.labelSmall,
            color = style.textTertiary,
        )
    }
}
