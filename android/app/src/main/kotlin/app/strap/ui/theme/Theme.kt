package app.strap.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.strap.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** One metric family's colours: the accent (arcs, lines, labels) and its tonal container pair. */
@Immutable
data class MetricTone(val accent: Color, val container: Color, val onContainer: Color)

/**
 * Metric colours — one per metric family, the same on every screen ("Performance" design,
 * docs/design handoff). The plain fields are the accents most drawing code wants.
 */
@Immutable
data class MetricColors(
    val recoveryTone: MetricTone,
    val strainTone: MetricTone,
    val sleepTone: MetricTone,
    val heartTone: MetricTone,
    val stressTone: MetricTone,
    val stepsTone: MetricTone,
    /** Gauge tracks, chart gridlines, empty bar tracks (`surface3`). */
    val track: Color,
    val stages: StageColors,
) {
    val recovery get() = recoveryTone.accent
    val strain get() = strainTone.accent
    val sleep get() = sleepTone.accent
    val heart get() = heartTone.accent
    val stress get() = stressTone.accent
    val steps get() = stepsTone.accent
}

/**
 * Sleep stages, one hue each (DESIGN U2): deep and REM were two shades of one purple. Deep
 * stays the sleep family's darkest violet, light its palest, REM moves to cyan and awake to
 * amber, so the hypnogram reads by hue as well as by row.
 */
@Immutable
data class StageColors(val deep: Color, val light: Color, val rem: Color, val awake: Color)

/** The neutrals and zone colours Material's scheme has no slot for. */
@Immutable
data class RidgeColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val card: Color,
    val surface3: Color,
    val surface4: Color,
    val zoneGreen: Color,
    val zoneYellow: Color,
    val zoneRed: Color,
    /** 1 px lines that separate surfaces: the design has no drop shadows (DESIGN v2). */
    val hairline: Color = surface3,
    val ink: Color = Color.Unspecified,
    /** For text 14 sp and up only (≈4:1); smaller text uses onSurfaceVariant. */
    val inkFaint: Color = Color.Unspecified,
    val dark: Boolean = true,
) {
    /** Recovery zone: ≥ 67 green, 34–66 yellow, below red. */
    fun zone(recovery: Double): Color = when {
        recovery >= 67 -> zoneGreen
        recovery >= 34 -> zoneYellow
        else -> zoneRed
    }
}

/**
 * A theme's raw palette (docs/denis/DESIGN.md "v2"): near-black and nearly colourless, so colour
 * only ever means data. Every metric colour is ≥ 4.8:1 on its card (checked per theme).
 */
private data class Palette(
    val dark: Boolean,
    val bg: Long, val card: Long, val surface2: Long, val surface3: Long, val surface4: Long,
    val ink: Long, val muted: Long, val faint: Long, val hairline: Long, val accent: Long,
    val green: Long, val yellow: Long, val red: Long,
    val strain: Long, val sleep: Long, val heart: Long, val stress: Long, val steps: Long, val rem: Long,
)

/** The themes on offer in Settings → Appearance; [AUTO] follows the system: Midnight or Daylight. */
enum class RidgeTheme(val label: String, val blurb: String) {
    AUTO("Auto", "Midnight or Daylight, with your phone"),
    MIDNIGHT("Midnight", "Near-black, the default"),
    VOID("Void", "True black for OLED"),
    DAYLIGHT("Daylight", "Light, for bright rooms"),
    AURORA("Aurora", "Deep teal, lavender accent"),
}

private val PALETTES = mapOf(
    RidgeTheme.MIDNIGHT to Palette(true, 0xFF08090B, 0xFF101114, 0xFF16181C, 0xFF1F2227, 0xFF2A2E34,
        0xFFF2F4F7, 0xFF9AA0AA, 0xFF6B717B, 0xFF262A30, 0xFFF2F4F7,
        0xFF34E07A, 0xFFFFD23F, 0xFFFF4D5E, 0xFF4AA8FF, 0xFFA88BFF, 0xFFFF6B8E, 0xFFFF9A3D, 0xFF2FD9C4, 0xFF5CC8F0),
    RidgeTheme.VOID to Palette(true, 0xFF000000, 0xFF0A0A0A, 0xFF121212, 0xFF1B1B1B, 0xFF262626,
        0xFFFFFFFF, 0xFFA1A1A1, 0xFF737373, 0xFF232323, 0xFFFFFFFF,
        0xFF30D46F, 0xFFF5C842, 0xFFF2495A, 0xFF3F9BF2, 0xFF9F86F5, 0xFFF2648A, 0xFFF29340, 0xFF2CCDB9, 0xFF55BDE6),
    RidgeTheme.DAYLIGHT to Palette(false, 0xFFF3F4F6, 0xFFFFFFFF, 0xFFECEEF1, 0xFFE2E5E9, 0xFFD6DADF,
        0xFF0B0D10, 0xFF565D67, 0xFF7A818B, 0xFFD9DDE2, 0xFF0B0D10,
        0xFF12813F, 0xFF946200, 0xFFC8283B, 0xFF1A66D6, 0xFF6A47D9, 0xFFC42C63, 0xFFB35300, 0xFF0B7F74, 0xFF1F8FBF),
    RidgeTheme.AURORA to Palette(true, 0xFF03191A, 0xFF062224, 0xFF0A2C2E, 0xFF10383A, 0xFF174446,
        0xFFEEFFFD, 0xFFA3BFBD, 0xFF6F8E8C, 0xFF1A4446, 0xFFE9C8FF,
        0xFF3FE38A, 0xFFFFD54D, 0xFFFF5C6C, 0xFF5AB0FF, 0xFFB9A0FF, 0xFFFF7A9A, 0xFFFFA14D, 0xFF7FE7F2, 0xFF6FD3F2),
)

/** A metric's accent, a tinted wash of it over the card, and readable text on that wash. */
private fun tone(accent: Color, p: Palette): MetricTone {
    val card = Color(p.card)
    return MetricTone(accent, lerp(card, accent, if (p.dark) 0.16f else 0.12f), lerp(accent, Color(p.ink), if (p.dark) 0.45f else 0.55f))
}

private fun ridgeOf(p: Palette) = RidgeColors(
    bg = Color(p.bg), surface1 = Color(p.bg), surface2 = Color(p.surface2), card = Color(p.card),
    surface3 = Color(p.surface3), surface4 = Color(p.surface4),
    zoneGreen = Color(p.green), zoneYellow = Color(p.yellow), zoneRed = Color(p.red),
    hairline = Color(p.hairline), ink = Color(p.ink), inkFaint = Color(p.faint), dark = p.dark,
)

private fun metricsOf(p: Palette) = MetricColors(
    recoveryTone = tone(Color(p.green), p),
    strainTone = tone(Color(p.strain), p),
    sleepTone = tone(Color(p.sleep), p),
    heartTone = tone(Color(p.heart), p),
    stressTone = tone(Color(p.stress), p),
    stepsTone = tone(Color(p.steps), p),
    track = Color(p.surface3),
    // One sleep family in three depths; REM and awake keep their own hues (DESIGN U2).
    // Deep is the denser shade in every theme: it sinks towards the background on dark themes and
    // towards the ink on light ones; light goes the other way.
    stages = StageColors(
        deep = if (p.dark) lerp(Color(p.sleep), Color(p.bg), 0.25f) else lerp(Color(p.sleep), Color(p.ink), 0.3f),
        light = if (p.dark) lerp(Color(p.sleep), Color(p.ink), 0.45f).let { lerp(it, Color(p.card), 0.15f) } else lerp(Color(p.sleep), Color(p.bg), 0.5f),
        rem = Color(p.rem), awake = Color(p.stress),
    ),
)

private fun schemeOf(p: Palette): ColorScheme {
    val ink = Color(p.ink)
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        background = Color(p.bg), onBackground = ink, surface = Color(p.bg), onSurface = ink, onSurfaceVariant = Color(p.muted),
        surfaceVariant = Color(p.surface3), surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color(p.bg), surfaceContainerLow = Color(p.surface2), surfaceContainer = Color(p.card),
        surfaceContainerHigh = Color(p.surface3), surfaceContainerHighest = Color(p.surface4),
        outline = Color(p.faint), outlineVariant = Color(p.hairline),
        // Primary is ink (or the theme's one accent): buttons are neutral, colour is for data.
        primary = Color(p.accent), onPrimary = Color(p.bg),
        primaryContainer = Color(p.surface3), onPrimaryContainer = ink,
        secondary = Color(p.accent), onSecondary = Color(p.bg),
        secondaryContainer = Color(p.surface3), onSecondaryContainer = ink,
        inverseSurface = ink, inverseOnSurface = Color(p.bg), inversePrimary = Color(p.surface3),
        error = Color(p.red), onError = Color(p.bg),
    )
}

val LocalMetricColors = staticCompositionLocalOf { metricsOf(PALETTES.getValue(RidgeTheme.MIDNIGHT)) }
val LocalRidgeColors = staticCompositionLocalOf { ridgeOf(PALETTES.getValue(RidgeTheme.MIDNIGHT)) }

/** The swatch a theme shows in the picker: background, card and accent. */
fun themeSwatch(theme: RidgeTheme, systemDark: Boolean): Triple<Color, Color, Color> {
    val p = PALETTES.getValue(resolve(theme, systemDark))
    return Triple(Color(p.bg), Color(p.card), Color(p.green))
}

private fun resolve(theme: RidgeTheme, systemDark: Boolean) =
    if (theme == RidgeTheme.AUTO) (if (systemDark) RidgeTheme.MIDNIGHT else RidgeTheme.DAYLIGHT) else theme

/** Geist for words and numbers, Geist Mono for axes and timers (OFL, bundled; DESIGN v2). */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)
val GeistMono = FontFamily(Font(R.font.geist_mono_regular, FontWeight.Normal), Font(R.font.geist_mono_medium, FontWeight.Medium))

/** Tabular figures: Geist's digits are proportional by default, and numbers mustn't jiggle as they change. */
private const val TNUM = "tnum"

/** The type scale: Geist, 400 for body, 500 for titles and hero numbers; tight tracking on big figures. */
object RidgeType {
    val topTitle = TextStyle(fontFamily = Geist, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp)
    val topSubtitle = TextStyle(fontFamily = Geist, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val headline = TextStyle(fontFamily = Geist, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.7).sp, fontFeatureSettings = TNUM)
    val setupTitle = TextStyle(fontFamily = Geist, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.8).sp)
    val bigNumber = TextStyle(fontFamily = Geist, fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.4).sp, fontFeatureSettings = TNUM)
    val cardNumber = TextStyle(fontFamily = Geist, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp, fontFeatureSettings = TNUM)
    val rowValue = TextStyle(fontFamily = Geist, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp, fontFeatureSettings = TNUM)
    val sideValue = TextStyle(fontFamily = Geist, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp, fontFeatureSettings = TNUM)
    val sheetTitle = TextStyle(fontFamily = Geist, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp)
    val cardTitle = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp)
    val rowTitle = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val paragraph = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** Eyebrow labels: 11 sp, uppercase by the caller, +0.08 em. */
    val section = TextStyle(fontFamily = Geist, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp)
    val caption = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM)
    val change = TextStyle(fontFamily = Geist, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM)
    val unit = TextStyle(fontFamily = Geist, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
    /** Chart axes and timers: monospaced, so labels line up. */
    val axis = TextStyle(fontFamily = GeistMono, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal)
}

private val Type = Typography().let { t ->
    fun TextStyle.geist() = copy(fontFamily = Geist)
    t.copy(
        displayLarge = t.displayLarge.geist(), displayMedium = t.displayMedium.geist(), displaySmall = t.displaySmall.geist(),
        headlineLarge = t.headlineLarge.geist(), headlineMedium = t.headlineMedium.geist(), headlineSmall = t.headlineSmall.geist(),
        titleLarge = RidgeType.topTitle, titleMedium = t.titleMedium.geist().copy(fontWeight = FontWeight.Medium), titleSmall = t.titleSmall.geist(),
        bodyLarge = t.bodyLarge.geist(), bodyMedium = t.bodyMedium.geist(), bodySmall = t.bodySmall.geist(),
        labelLarge = t.labelLarge.geist().copy(fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.geist().copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), labelSmall = t.labelSmall.geist(),
    )
}

/** The chosen theme ([RidgeTheme]), with the status and navigation bar icons to match it. */
@Composable
fun StrapTheme(theme: RidgeTheme = RidgeTheme.AUTO, content: @Composable () -> Unit) {
    val p = PALETTES.getValue(resolve(theme, isSystemInDarkTheme()))
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !p.dark
                isAppearanceLightNavigationBars = !p.dark
            }
        }
    }
    CompositionLocalProvider(LocalMetricColors provides metricsOf(p), LocalRidgeColors provides ridgeOf(p)) {
        MaterialTheme(colorScheme = schemeOf(p), typography = Type, shapes = Shapes, content = content)
    }
}

/** Cards 20, tiles and buttons 12, chips 8: rounded rectangles, not pills (DESIGN v2). */
private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)
