package app.strap.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import kotlinx.coroutines.launch

/** Kinetics' number counter, spring(280, 18) as Compose: damping 0.54, stiffness 280 (DESIGN v2). */
private val DigitSpring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.54f, stiffness = 280f)

/**
 * A number whose digits roll independently when they change (a timer ticking, a day switched):
 * the changed digit slides up out of the way as the new one slides in. Unchanged digits stay
 * still, so the eye goes to what moved.
 */
@Composable
fun SlidingNumber(text: String, style: TextStyle, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    (slideInVertically(DigitSpring) { it } + fadeIn(tween(120))).togetherWith(slideOutVertically(DigitSpring) { -it } + fadeOut(tween(90)))
                },
                label = "digit$i",
            ) { c -> Text(c.toString(), style = style, color = color) }
        }
    }
}

/**
 * Press and hold to confirm (Kinetics' "hold to confirm"): a ring fills over [holdMs]; letting go
 * early drains it and nothing happens; a full ring fires [onConfirm] with a confirm haptic. For
 * ending things you don't want ended by a pocket tap: a running workout.
 */
@Composable
fun HoldToConfirm(color: Color, track: Color, size: Dp = 56.dp, holdMs: Int = 800, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.size(size).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    fill.animateTo(1f, tween((holdMs * (1 - fill.value)).toInt(), easing = LinearEasing))
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onConfirm()
                    fill.snapTo(0f)
                }
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (!tryAwaitRelease() || fill.value < 1f) {
                    job.cancel()
                    scope.launch { fill.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = 3.dp.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(w))
            drawArc(color, -90f, 360f * fill.value, false, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}
