package it.spotifystats.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette scura ispirata a Spotify.
 *
 * Nota sul verde: #1DB954 è il colore del marchio Spotify. Le sue brand
 * guidelines non consentono di usarlo come colore identitario di un'app di
 * terze parti. Finché l'app resta privata non è un problema; se un giorno
 * finisse sul Play Store basta cambiare [Accent] qui e l'intera interfaccia
 * segue, perché nessuna schermata scrive un colore a mano.
 */
val Background = Color(0xFF121212)
val Surface = Color(0xFF181818)
val SurfaceElevated = Color(0xFF282828)
val SurfaceHighlight = Color(0xFF333333)
val Outline = Color(0xFF2A2A2A)

val Accent = Color(0xFF1DB954)
val AccentBright = Color(0xFF1ED760)
val AccentMuted = Color(0xFF14833B)

val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB3B3B3)
val TextTertiary = Color(0xFF727272)

val Danger = Color(0xFFE22134)
val Warning = Color(0xFFF5A623)

/** Colori usati per distinguere le serie nei grafici e i generi in classifica. */
val ChartPalette = listOf(
    Color(0xFF1DB954),
    Color(0xFF2D9CDB),
    Color(0xFFBB6BD9),
    Color(0xFFF2994A),
    Color(0xFFEB5757),
    Color(0xFF56CCF2),
)
