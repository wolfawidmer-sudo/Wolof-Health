package ch.widmer.airview.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ch.widmer.airview.R

/**
 * Farben kodieren den Datentyp, nicht Dekoration:
 * move = Bewegung, heart = Puls, sleep/sleepLight = Schlafphasen, awake = Wachphasen.
 */
@Immutable
data class Palette(
    val bg: Color, val ink: Color, val muted: Color, val track: Color,
    val move: Color, val heart: Color, val sleep: Color, val sleepLight: Color, val awake: Color,
    val bar: Color, val isDark: Boolean,
)

private val Light = Palette(
    bg = Color(0xFFE6EBEE), ink = Color(0xFF102A3A), muted = Color(0xFF5B6E7A), track = Color(0xFFCCD6DC),
    move = Color(0xFF2D4BD8), heart = Color(0xFFD0284F), sleep = Color(0xFF5A3DB8),
    sleepLight = Color(0xFFAE9DE3), awake = Color(0xFFDB8A1C),
    bar = Color(0xFFF8FAFB), isDark = false,
)
private val Dark = Palette(
    bg = Color(0xFF0F1E29), ink = Color(0xFFE3EAEE), muted = Color(0xFF8397A3), track = Color(0xFF223543),
    move = Color(0xFF7C93FF), heart = Color(0xFFFF5C7D), sleep = Color(0xFFA48BFF),
    sleepLight = Color(0xFF55479A), awake = Color(0xFFF2B04B),
    bar = Color(0xFF1B2D3A), isDark = true,
)

/** Die Schlafseite ist immer Nacht – beim Wischen dorthin wird es dunkel. */
private val NightOnLight = Palette(
    bg = Color(0xFF1A1640), ink = Color(0xFFEDEAFF), muted = Color(0xFFA19CCF), track = Color(0xFF2D2862),
    move = Color(0xFF8FA2FF), heart = Color(0xFFFF6F8C), sleep = Color(0xFFB09BFF),
    sleepLight = Color(0xFF6A5BC4), awake = Color(0xFFF4B656),
    bar = Color(0xFF262058), isDark = true,
)
private val NightOnDark = NightOnLight.copy(bg = Color(0xFF110E2C), track = Color(0xFF242050), bar = Color(0xFF1E1A48))

@Composable
fun basePalette(): Palette = if (isSystemInDarkTheme()) Dark else Light

@Composable
fun nightPalette(): Palette = if (isSystemInDarkTheme()) NightOnDark else NightOnLight

fun lerpPalette(a: Palette, b: Palette, t: Float): Palette {
    fun l(x: Color, y: Color) = androidx.compose.ui.graphics.lerp(x, y, t)
    return Palette(
        l(a.bg, b.bg), l(a.ink, b.ink), l(a.muted, b.muted), l(a.track, b.track), l(a.move, b.move),
        l(a.heart, b.heart), l(a.sleep, b.sleep), l(a.sleepLight, b.sleepLight), l(a.awake, b.awake),
        l(a.bar, b.bar), if (t < .5f) a.isDark else b.isDark,
    )
}

/** Fortschritt 0..1 der Einblend-Animation der aktuellen Seite. */
val LocalReveal = androidx.compose.runtime.compositionLocalOf { 1f }

val LocalPalette = staticCompositionLocalOf { Light }

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** Archivo in zwei Breiten: extra-schmal und fett für Messwerte, normal für Text. */
object Type {
    private val numeralFamily = FontFamily(archivo(720, 62f))
    private val textFamily = FontFamily(archivo(400, 100f), archivo(600, 100f), archivo(500, 100f))

    val numeral = TextStyle(fontFamily = numeralFamily, letterSpacing = (-0.5).sp)
    val title = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(600), fontSize = 18.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 17.sp)
}

@Composable
fun AirTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = p.bg, surface = p.bg, onBackground = p.ink, onSurface = p.ink, primary = p.move,
    )
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}
