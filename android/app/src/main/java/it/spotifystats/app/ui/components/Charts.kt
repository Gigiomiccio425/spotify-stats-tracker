package it.spotifystats.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextTertiary

/**
 * Istogramma disegnato a mano su Canvas.
 *
 * Niente libreria di grafici: servono due diagrammi a barre e nient'altro, e
 * una dipendenza esterna porterebbe vincoli di versione su Compose senza dare
 * in cambio nulla che qui serva davvero.
 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = Accent,
    trackColor: Color = SurfaceElevated,
    heightDp: Int = 120,
) {
    val maxValue = values.maxOrNull() ?: 0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        if (values.isEmpty()) return@Canvas

        // Con molte barre lo spazio fra una e l'altra si assottiglia da sé,
        // così un anno di dati giornalieri resta leggibile quanto una settimana.
        val slot = size.width / values.size
        val gap = (slot * 0.25f).coerceAtMost(4f)
        val barWidth = (slot - gap).coerceAtLeast(1f)
        val radius = CornerRadius(barWidth / 2, barWidth / 2)

        values.forEachIndexed { index, value ->
            val x = index * slot + gap / 2
            // Traccia di fondo: mostra i giorni a zero, che altrimenti
            // sparirebbero e darebbero l'impressione di dati mancanti.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(x, size.height - 2f),
                size = Size(barWidth, 2f),
                cornerRadius = radius,
            )
            if (maxValue > 0f && value > 0f) {
                val barHeight = (value / maxValue) * size.height
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius,
                )
            }
        }
    }
}

@Composable
fun LabeledBarChart(
    values: List<Float>,
    startLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier,
    heightDp: Int = 120,
) {
    Column(modifier.fillMaxWidth()) {
        BarChart(values, heightDp = heightDp, modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(startLabel, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            Text(endLabel, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

/**
 * Ascolti per giorno della settimana, da lunedì a domenica.
 * Le etichette stanno sotto ogni barra: con sette valori c'è spazio, e senza
 * si dovrebbe contare a mente per capire quale colonna è il sabato.
 */
@Composable
fun WeekdayChart(counts: List<Int>, modifier: Modifier = Modifier) {
    val labels = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")
    val max = counts.maxOrNull() ?: 0

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        counts.forEachIndexed { index, value ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (value > 0) "$value" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        // Altezza proporzionale, con un minimo visibile: una
                        // barra alta zero pixel sembrerebbe un dato mancante.
                        .height(if (max > 0) (8 + (value.toFloat() / max) * 72).dp else 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (value > 0) Accent else SurfaceElevated),
                )
                Text(
                    labels.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
        }
    }
}

/**
 * Ascolti per ora del giorno. Le 24 barre sono sempre tutte presenti, anche a
 * zero: un grafico che salta le ore vuote farebbe sembrare le 4 del mattino
 * adiacenti alle 8.
 */
@Composable
fun ListeningClock(hours: List<Int>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        BarChart(
            values = hours.map { it.toFloat() },
            heightDp = 100,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("00", "06", "12", "18", "23").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            }
        }
    }
}
