package it.spotifystats.app.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formattazioni condivise. Tutte in italiano e nel fuso del telefono. */
object Format {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
    private val shortDayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

    /** 1h 23m, oppure 45m, oppure 12s per gli ascolti brevissimi. */
    fun duration(ms: Long): String {
        val d = Duration.ofMillis(ms)
        val hours = d.toHours()
        val minutes = d.toMinutes() % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${d.seconds}s"
        }
    }

    /** Minuti con separatore delle migliaia: 12.480 si legge, 12480 no. */
    fun minutes(minutes: Int): String = "%,d".format(Locale.ITALIAN, minutes)

    fun number(value: Int): String = "%,d".format(Locale.ITALIAN, value)

    fun trackLength(ms: Int): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun date(iso: String?): String =
        iso?.let { dayFormatter.format(Instant.parse(it).atZone(zone)) } ?: "—"

    fun shortDate(iso: String?): String =
        iso?.let { shortDayFormatter.format(Instant.parse(it).atZone(zone)) } ?: "—"

    fun time(iso: String?): String =
        iso?.let { timeFormatter.format(Instant.parse(it).atZone(zone)) } ?: "—"

    /** "oggi", "ieri" o la data estesa: usato per le intestazioni dello storico. */
    fun relativeDay(iso: String): String {
        val date = Instant.parse(iso).atZone(zone).toLocalDate()
        val today = Instant.now().atZone(zone).toLocalDate()
        return when (date) {
            today -> "Oggi"
            today.minusDays(1) -> "Ieri"
            else -> dayFormatter.format(date).replaceFirstChar { it.uppercase() }
        }
    }

    fun dayKey(iso: String): String = Instant.parse(iso).atZone(zone).toLocalDate().toString()

    /** "da 34 giorni" per la data di inizio tracciamento. */
    fun daysSince(iso: String): Long =
        Duration.between(Instant.parse(iso), Instant.now()).toDays()
}
