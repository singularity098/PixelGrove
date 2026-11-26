package app.pixelg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.pixelg.core.Prefs

/**
 * PixelGrove's nine themes (web/src/app.css), plus following the system. Dark, Light and
 * AMOLED use blue instead of the web UI's green, to match PixelG's blue icon.
 */
data class Palette(
    val id: String,
    val label: String,
    val dark: Boolean,
    val bg: Long,
    val surface: Long,
    val surface2: Long,
    val border: Long,
    val text: Long,
    val muted: Long,
    val accent: Long,
    val onAccent: Long,
    val danger: Long,
)

val palettes = listOf(
    Palette("dark", "Dark", true, 0xFF0F1113, 0xFF181B1F, 0xFF22262B, 0xFF2C3137, 0xFFE8EAED, 0xFF9AA0A6, 0xFF5CB0F5, 0xFF06121F, 0xFFF28B82),
    Palette("light", "Light", false, 0xFFF7F8F9, 0xFFFFFFFF, 0xFFEEF0F2, 0xFFDDE1E5, 0xFF1B1F23, 0xFF5F6670, 0xFF2A72BF, 0xFFFFFFFF, 0xFFC5221F),
    Palette("amoled", "AMOLED black", true, 0xFF000000, 0xFF0A0A0A, 0xFF161616, 0xFF262626, 0xFFE8E8E8, 0xFF8F8F8F, 0xFF5CB0F5, 0xFF000000, 0xFFFF6B6B),
    Palette("dracula", "Dracula", true, 0xFF21222C, 0xFF282A36, 0xFF343746, 0xFF44475A, 0xFFF8F8F2, 0xFFA4ABCD, 0xFFBD93F9, 0xFF21222C, 0xFFFF5555),
    Palette("tokyo-night", "Tokyo Night", true, 0xFF1A1B26, 0xFF1F2335, 0xFF292E42, 0xFF3B4261, 0xFFC0CAF5, 0xFF9AA5CE, 0xFF7AA2F7, 0xFF1A1B26, 0xFFF7768E),
    Palette("catppuccin-mocha", "Catppuccin Mocha", true, 0xFF181825, 0xFF1E1E2E, 0xFF313244, 0xFF45475A, 0xFFCDD6F4, 0xFFA6ADC8, 0xFFCBA6F7, 0xFF11111B, 0xFFF38BA8),
    Palette("catppuccin-macchiato", "Catppuccin Macchiato", true, 0xFF1E2030, 0xFF24273A, 0xFF363A4F, 0xFF494D64, 0xFFCAD3F5, 0xFFA5ADCB, 0xFFC6A0F6, 0xFF181926, 0xFFED8796),
    Palette("catppuccin-frappe", "Catppuccin Frappé", true, 0xFF292C3C, 0xFF303446, 0xFF414559, 0xFF51576D, 0xFFC6D0F5, 0xFFA5ADCE, 0xFFCA9EE6, 0xFF232634, 0xFFE78284),
    Palette("catppuccin-latte", "Catppuccin Latte", false, 0xFFE6E9EF, 0xFFEFF1F5, 0xFFDCE0E8, 0xFFCCD0DA, 0xFF4C4F69, 0xFF6C6F85, 0xFF8839EF, 0xFFEFF1F5, 0xFFD20F39),
)

private fun Palette.scheme(): ColorScheme {
    val c = { v: Long -> Color(v) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(accent),
        onPrimary = c(onAccent),
        primaryContainer = c(surface2),
        onPrimaryContainer = c(text),
        secondary = c(accent),
        onSecondary = c(onAccent),
        secondaryContainer = c(surface2),
        onSecondaryContainer = c(text),
        tertiary = c(accent),
        background = c(bg),
        onBackground = c(text),
        surface = c(bg),
        onSurface = c(text),
        surfaceVariant = c(surface2),
        onSurfaceVariant = c(muted),
        surfaceContainerLowest = c(bg),
        surfaceContainerLow = c(surface),
        surfaceContainer = c(surface),
        surfaceContainerHigh = c(surface2),
        surfaceContainerHighest = c(surface2),
        surfaceBright = c(surface2),
        surfaceDim = c(bg),
        inverseSurface = c(text),
        inverseOnSurface = c(bg),
        outline = c(border),
        outlineVariant = c(border),
        error = c(danger),
        onError = c(bg),
    )
}

fun currentPalette(systemDark: Boolean): Palette {
    val id = Prefs.theme
    return palettes.firstOrNull { it.id == id }
        ?: if (systemDark) palettes[0] else palettes[1]
}

@Composable
fun PixelGTheme(content: @Composable () -> Unit) {
    val palette = currentPalette(isSystemInDarkTheme())
    MaterialTheme(colorScheme = palette.scheme(), content = content)
}
