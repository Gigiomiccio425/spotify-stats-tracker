package it.spotifystats.app.ui.recap

import androidx.compose.ui.graphics.Color

/**
 * Aspetto della card condivisibile.
 *
 * Le copertine degli album non cambiano mai con lo stile: le linee guida di
 * Spotify vietano di alterarle, quindi i temi agiscono solo su sfondo, testo e
 * colore d'accento.
 */
data class CardStyle(
    val id: String,
    val name: String,
    /** Due o tre fermate di un gradiente verticale. */
    val background: List<Color>,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Riquadro dietro le copertine, per staccarle dagli sfondi chiari. */
    val artworkBackdrop: Color,
)

/** Proporzioni dell'immagine esportata. */
enum class CardFormat(val label: String, val ratio: Float, val width: Int, val height: Int) {
    Story("Storia", 9f / 16f, 1080, 1920),
    Square("Post", 1f, 1080, 1080),
}

val CardStyles: List<CardStyle> = listOf(
    CardStyle(
        id = "notte",
        name = "Notte",
        background = listOf(Color(0xFF1F1F1F), Color(0xFF121212), Color(0xFF0A1F12)),
        accent = Color(0xFF1DB954),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF727272),
        artworkBackdrop = Color(0xFF282828),
    ),
    CardStyle(
        id = "neon",
        name = "Neon",
        background = listOf(Color(0xFF1A0B2E), Color(0xFF2D1B4E), Color(0xFF0F0524)),
        accent = Color(0xFFFF3D9A),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFC9B8E8),
        textTertiary = Color(0xFF8A76B0),
        artworkBackdrop = Color(0xFF3A2560),
    ),
    CardStyle(
        id = "tramonto",
        name = "Tramonto",
        background = listOf(Color(0xFF3D1F1F), Color(0xFF7A2E1E), Color(0xFFC2571A)),
        accent = Color(0xFFFFD166),
        textPrimary = Color(0xFFFFF6EC),
        textSecondary = Color(0xFFF0C9A8),
        textTertiary = Color(0xFFC79A78),
        artworkBackdrop = Color(0xFF5A2A1C),
    ),
    CardStyle(
        id = "carta",
        name = "Carta",
        background = listOf(Color(0xFFF7F3EA), Color(0xFFEDE6D6)),
        accent = Color(0xFF1A6B3C),
        textPrimary = Color(0xFF1A1A1A),
        textSecondary = Color(0xFF5C5647),
        textTertiary = Color(0xFF8C8474),
        artworkBackdrop = Color(0xFFDDD4C2),
    ),
    CardStyle(
        id = "oceano",
        name = "Oceano",
        background = listOf(Color(0xFF04202F), Color(0xFF06384F), Color(0xFF02121B)),
        accent = Color(0xFF35D0E8),
        textPrimary = Color(0xFFEAF7FB),
        textSecondary = Color(0xFF9EC5D4),
        textTertiary = Color(0xFF6B94A5),
        artworkBackdrop = Color(0xFF0A3346),
    ),
    CardStyle(
        id = "inchiostro",
        name = "Inchiostro",
        background = listOf(Color(0xFF000000), Color(0xFF000000)),
        accent = Color(0xFFFFFFFF),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFF9E9E9E),
        textTertiary = Color(0xFF616161),
        artworkBackdrop = Color(0xFF1A1A1A),
    ),
)

val DefaultCardStyle: CardStyle = CardStyles.first()
