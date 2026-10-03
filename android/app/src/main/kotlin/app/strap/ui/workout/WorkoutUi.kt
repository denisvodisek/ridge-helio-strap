package app.strap.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.HoldToConfirm
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SlidingNumber
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import org.json.JSONObject

/** One sport as a tappable tile: icon over name, filled when chosen. */
@Composable
private fun SportTile(sport: Sport, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tone = LocalMetricColors.current.strainTone
    val bg = if (selected) tone.accent else LocalRidgeColors.current.surface3
    val fg = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(sport.icon, null, tint = fg)
        Text(sport.label, style = RidgeType.label, color = fg)
    }
}

/** Sports in rows of four, most recent first: the first row is almost always the one you want. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportGrid(sports: List<Sport>, selected: Sport?, onPick: (Sport) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
        sports.forEach { s -> SportTile(s, s == selected, Modifier.weight(1f)) { onPick(s) } }
        // Fill the last row with empty slots so its tiles keep the same width as the rows above.
        repeat((4 - sports.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
    }
}

private enum class Mode(val label: String) { NOW("Start now"), PAST("Log a past one") }

/**
 * The one place a workout starts (DESIGN: as easy as possible). Pick a sport (your last one is
 * already chosen) and tap Start; or switch to "Log a past one" and set when it began and how
 * long it ran. Everything else (load, zones, strain, recovery) the server works out from the HR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSheet(store: WorkoutStore, onDismiss: () -> Unit, onStart: (Sport) -> Unit, onLog: (Sport, Instant, Instant) -> Unit) {
    val sports = remember { store.recentFirst() }
    var sport by remember { mutableStateOf(sports.first()) }
    var mode by remember { mutableStateOf(Mode.NOW) }
    val oneHourAgo = remember { LocalTime.now().minusHours(1) }
    val time = rememberTimePickerState(oneHourAgo.hour, oneHourAgo.minute, is24Hour = true)
    var minutes by remember { mutableStateOf(60) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalRidgeColors.current.card) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Workout", style = RidgeType.sheetTitle)
            SportGrid(sports, sport) { sport = it }
            ConnectedButtons(Mode.entries, mode, { it.label }) { mode = it }
            AnimatedVisibility(mode == Mode.PAST, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Started at", style = RidgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TimeInput(time)
                    Text("Lasted", style = RidgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ConnectedButtons(listOf(30, 45, 60, 90, 120), minutes, { if (it < 60) "$it m" else "${it / 60}${if (it % 60 == 0) "" else ".5"} h" }) { minutes = it }
                }
            }
            Button(
                onClick = {
                    if (mode == Mode.NOW) onStart(sport) else {
                        // Today unless that time is still ahead, then it was yesterday (a late-night log).
                        val zone = ZoneId.systemDefault()
                        var start = LocalDate.now().atTime(time.hour, time.minute).atZone(zone).toInstant()
                        if (start.isAfter(Instant.now())) start = start.minusSeconds(86_400)
                        onLog(sport, start, start.plusSeconds(minutes * 60L))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.medium,
            ) { Text(if (mode == Mode.NOW) "Start ${sport.label.lowercase()}" else "Save ${sport.label.lowercase()}", style = RidgeType.cardTitle) }
        }
    }
}

/**
 * The workout in progress, pinned above the screen: sport, a timer that ticks every second, and
 * Stop. The timer is "now − start", so it's right after the app was closed, killed or rebooted.
 */
@Composable
fun OngoingBar(ongoing: Ongoing, onStop: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val tone = LocalMetricColors.current.strainTone
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ongoing) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val s = ((now - ongoing.start.toEpochMilli()) / 1000).coerceAtLeast(0)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(tone.container).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tone.accent), contentAlignment = Alignment.Center) {
            Icon(ongoing.sport.icon, null, tint = MaterialTheme.colorScheme.surface)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(ongoing.sport.label, style = RidgeType.label, color = tone.onContainer)
            SlidingNumber("%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60), RidgeType.rowValue.copy(fontFeatureSettings = "tnum"), tone.onContainer)
            Text("Hold ■ to finish", style = RidgeType.caption, color = tone.onContainer.copy(alpha = 0.7f))
        }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Discard workout", tint = tone.onContainer) }
        HoldToConfirm(tone.onContainer, tone.onContainer.copy(alpha = 0.2f), onConfirm = onStop) {
            Icon(Icons.Rounded.Stop, "Hold to finish the workout", tint = tone.onContainer)
        }
    }
}

/**
 * "Looks like a workout" (SPEC S4): the server spotted sustained effort nothing else explains.
 * One tap on a sport makes it a session; "Not a workout" makes it go away for good.
 */
@Composable
fun SuggestionCard(s: JSONObject, sports: List<Sport>, onConfirm: (Sport) -> Unit, onDismiss: () -> Unit) {
    RidgeCard(spacing = 10.dp) {
        Text("Looks like a workout", style = RidgeType.cardTitle)
        Subtle("${clockOf(s.getLong("start"))}–${clockOf(s.getLong("end"))} · ${s.getInt("minutes")} min · avg ${s.getInt("avg_hr")} bpm, peak ${s.getInt("peak_hr")}")
        SportGrid(sports.take(4), null, onConfirm)
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Not a workout") }
    }
}
