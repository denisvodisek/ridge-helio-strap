package app.strap.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** A coloured one-liner under a number: a change ("▲ 8 vs week") or a target note. */
data class Note(val text: String, val color: Color)

/**
 * "▲ 8 vs week": green when the change is good, red when bad, muted under half a unit (or
 * when [neutral] — a change that is neither). [lowerBetter] for resting HR, breathing, stress.
 */
@Composable
fun changeNote(
    delta: Double?,
    suffix: String,
    digits: Int = 0,
    lowerBetter: Boolean = false,
    neutral: Boolean = false,
    magnitude: ((Double) -> String)? = null,
): Note? {
    if (delta == null || delta.isNaN()) return null
    val r = LocalRidgeColors.current
    val small = abs(delta) < 0.5 / Math.pow(10.0, digits.toDouble())
    val good = if (lowerBetter) delta < 0 else delta > 0
    val color = when {
        small || neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        good -> r.zoneGreen
        else -> r.zoneRed
    }
    val size = magnitude?.invoke(abs(delta)) ?: if (digits == 0) "${abs(delta).roundToInt()}" else "%.${digits}f".format(abs(delta))
    val arrow = if (size.trim('0', '.', ',').isEmpty()) "±" else if (delta < 0) "▼ " else "▲ "
    return Note("$arrow$size $suffix", color)
}

/**
 * The 270° gauge: starts at −135° from 12 o'clock, runs clockwise; the track resumes 9°
 * after the value's end. [fraction] null = withheld — an empty track and "—", never a zero.
 * The number counts up with the arc as it draws (DESIGN U8); with system animations off
 * both land at once, because Compose follows the animator duration scale.
 */
@Composable
fun Gauge(value: String, unit: String?, fraction: Float?, color: Color, size: Dp, valueSize: TextUnit, stroke: Float = 7f, crown: Boolean = false) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(fraction) { progress.animateTo((fraction ?: 0f).coerceIn(0f, 1f), tween(900, easing = EaseOutCubic)) }
    val shown = countingUp(value, fraction, progress.value)
    val track = LocalRidgeColors.current.surface3
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val scale = this.size.minDimension / 100f
            val r = 42f * scale
            val w = stroke * scale
            val topLeft = Offset(center.x - r, center.y - r)
            val arc = Size(2 * r, 2 * r)
            val style = Stroke(w, cap = StrokeCap.Round)
            val start = 135f // −135° from 12 o'clock, in Compose's 3-o'clock-based degrees
            val sweep = if (fraction == null) 0f else 270f * progress.value
            val trackStart = if (sweep > 0.5f) start + sweep + 9f else start
            val trackSweep = start + 270f - trackStart
            if (trackSweep > 0f) drawArc(track, trackStart, trackSweep, false, topLeft, arc, style = style)
            if (sweep > 0.5f) drawArc(color, start, sweep, false, topLeft, arc, style = style)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (crown) Crown(size * 0.13f)
            Text(shown, fontSize = valueSize, lineHeight = valueSize, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = RidgeType.body.fontWeight, fontFeatureSettings = "tnum"))
            unit?.let { Text(it.uppercase(), style = RidgeType.unit, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
    }
}

private val LEADING_NUMBER = Regex("""^(\d+)(?:\.(\d+))?(.*)$""")

/** [value]'s leading number scaled by how far the arc has drawn ("62%" → "31%" halfway); text as is otherwise. */
private fun countingUp(value: String, fraction: Float?, drawn: Float): String {
    val m = LEADING_NUMBER.matchEntire(value) ?: return value
    // "7:23" or "3/5" is not one number with a unit: counting its first part would tick "1:23", "2:23"…
    if (m.groupValues[3].any { it.isDigit() }) return value
    if (fraction == null || fraction <= 0f || drawn >= fraction) return value
    val decimals = m.groupValues[2].length
    val target = (m.groupValues[1] + if (decimals > 0) "." + m.groupValues[2] else "").toDouble()
    return "%.${decimals}f".format(target * drawn / fraction) + m.groupValues[3]
}

/** Gauge + label (12/500 uppercase) + change line; the whole block is the tap target. */
@Composable
fun GaugeBlock(
    value: String,
    unit: String?,
    fraction: Float?,
    color: Color,
    label: String,
    note: Note?,
    size: Dp,
    valueSize: TextUnit,
    stroke: Float = 7f,
    modifier: Modifier = Modifier,
    crown: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Gauge(value, unit, fraction, color, size, valueSize, stroke, crown)
        Text(label.uppercase(), style = RidgeType.section)
        NoteLine(note)
    }
}

@Composable
fun NoteLine(note: Note?, modifier: Modifier = Modifier) {
    // Always takes its line, so gauges with and without a change stay aligned.
    Text(note?.text ?: " ", style = RidgeType.change, color = note?.color ?: Color.Unspecified, modifier = modifier, textAlign = TextAlign.Center)
}

/** A hero's side figure: value 24/400, label 11/500 uppercase, then its note. */
@Composable
fun SideStat(value: String, label: String, note: Note?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = RidgeType.sideValue)
        Text(label.uppercase(), style = RidgeType.change.copy(letterSpacing = 0.8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        NoteLine(note)
    }
}

/** The hero row (Sleep, Activity, Recovery, Strap): side stat · 168 dp gauge · side stat. */
@Composable
fun HeroRow(
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
    gauge: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        left(Modifier.weight(1f).padding(bottom = 36.dp))
        gauge()
        right(Modifier.weight(1f).padding(bottom = 36.dp))
    }
}

/** The standard hero gauge: 168 dp, 38 sp value. */
@Composable
fun HeroGauge(value: String, unit: String?, fraction: Float?, color: Color, label: String, note: Note?, crown: Boolean = false, onClick: (() -> Unit)? = null) =
    GaugeBlock(value, unit, fraction, color, label, note, 168.dp, 38.sp, crown = crown, onClick = onClick)

/**
 * The earned crown (DESIGN U10): a small gold crown that springs in above the number once the
 * arc has drawn. Oura's convention: noticed, never loud, and only for a genuinely good day.
 */
@Composable
private fun Crown(height: Dp) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(850)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
    }
    val gold = Color(0xFFE2B33C)
    Canvas(Modifier.size(height * 1.5f, height).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }) {
        val w = size.width
        val h = size.height
        val crown = Path().apply {
            moveTo(0.08f * w, 0.92f * h)
            lineTo(0.0f, 0.22f * h)
            lineTo(0.3f * w, 0.55f * h)
            lineTo(0.5f * w, 0.0f)
            lineTo(0.7f * w, 0.55f * h)
            lineTo(w, 0.22f * h)
            lineTo(0.92f * w, 0.92f * h)
            close()
        }
        drawPath(crown, gold)
    }
}
