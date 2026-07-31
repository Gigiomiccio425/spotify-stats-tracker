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

    /**
     * Una data che non si riesce a interpretare non deve far crashare una
     * schermata intera: il server potrebbe cambiare formato, e una riga con un
     * trattino al posto dell'ora resta leggibile.
     */
    private fun parse(iso: String?): Instant? =
        iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun date(iso: String?): String =
        parse(iso)?.let { dayFormatter.format(it.atZone(zone)) } ?: "—"

    fun shortDate(iso: String?): String =
        parse(iso)?.let { shortDayFormatter.format(it.atZone(zone)) } ?: "—"

    fun time(iso: String?): String =
        parse(iso)?.let { timeFormatter.format(it.atZone(zone)) } ?: "—"

    /** "oggi", "ieri" o la data estesa: usato per le intestazioni dello storico. */
    fun relativeDay(iso: String): String {
        val date = parse(iso)?.atZone(zone)?.toLocalDate() ?: return "Data sconosciuta"
        val today = Instant.now().atZone(zone).toLocalDate()
        return when (date) {
            today -> "Oggi"
            today.minusDays(1) -> "Ieri"
            else -> dayFormatter.format(date).replaceFirstChar { it.uppercase() }
        }
    }

    fun dayKey(iso: String): String =
        parse(iso)?.atZone(zone)?.toLocalDate()?.toString() ?: "sconosciuto"

    /** "da 34 giorni" per la data di inizio tracciamento. */
    fun daysSince(iso: String): Long =
        parse(iso)?.let { Duration.between(it, Instant.now()).toDays() } ?: 0
}
