package it.spotifystats.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.spotifystats.app.data.api.ReleaseYearStats
import it.spotifystats.app.ui.Format
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary
import java.time.Year

/**
 * Quanto è "vecchia" la musica ascoltata, in base all'anno di pubblicazione
 * degli album.
 *
 * Si mostrano media e mediana insieme perché divergono in modo istruttivo: chi
 * ascolta soprattutto novità ma ha una passione per un disco del 1975 vedrà la
 * media tirata indietro e la mediana ferma sull'oggi.
 */
@Composable
fun MusicalAgePanel(stats: ReleaseYearStats, modifier: Modifier = Modifier) {
    if (stats.coveredPlays == 0 || stats.averageYear == null) {
        EmptyState(
            title = "Ancora niente da analizzare",
            subtitle = "Serve qualche ascolto di album con una data di pubblicazione nota.",
            modifier = modifier,
        )
        return
    }

    val currentYear = Year.now().value
    val age = currentYear - stats.averageYear

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceElevated)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "LA TUA ETÀ MUSICALE",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            VerticalSpacer(4)
            Text(
                "${stats.averageYear}",
                style = MaterialTheme.typography.displayLarge,
                color = Accent,
            )
            Text(
                when {
                    age <= 1 -> "Ascolti quasi solo uscite recenti."
                    age <= 5 -> "In media la tua musica ha $age anni."
                    age <= 15 -> "In media la tua musica ha $age anni: un piede nel passato."
                    else -> "In media la tua musica ha $age anni. Sei di un'altra epoca."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MiniTile("${stats.medianYear}", "anno mediano")
            MiniTile("${stats.oldestYear}", "il più vecchio")
            MiniTile("${stats.newestYear}", "il più recente")
        }

        VerticalSpacer(20)
        SectionTitle("Per decennio")

        val maxDecade = stats.decades.maxOfOrNull { it.playCount } ?: 1
        stats.decades.sortedByDescending { it.playCount }.forEach { decade ->
            DecadeRow(
                label = "Anni ${decade.decade.toString().takeLast(2)}",
                sublabel = "${decade.decade}–${decade.decade + 9}",
                playCount = decade.playCount,
                share = decade.share,
                maxPlayCount = maxDecade,
            )
        }

        VerticalSpacer(16)
        Text(
            "Calcolata su ${Format.number(stats.coveredPlays)} ascolti. Gli album senza data " +
                "di pubblicazione sono esclusi: contarli come anno zero sposterebbe la media di secoli.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        VerticalSpacer(32)
    }
}

@Composable
private fun DecadeRow(
    label: String,
    sublabel: String,
    playCount: Int,
    share: Int,
    maxPlayCount: Int,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(sublabel, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            }
            Text(
                "$share%  ·  ${Format.number(playCount)}×",
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
            val fraction = if (maxPlayCount > 0) playCount.toFloat() / maxPlayCount else 0f
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
