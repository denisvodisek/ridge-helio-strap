package app.strap.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.Bar
import app.strap.ui.components.CardHeader
import app.strap.ui.components.DayLineChart
import app.strap.ui.components.GaugeBlock
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.components.Note
import app.strap.ui.components.Point
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionLabel
import app.strap.ui.components.SideStat
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * One workout: strain on the day's own 0–21 scale, the HR curve across the session, time in
 * each zone and how fast HR came down after (SPEC S3). Your own sessions can change sport or
 * be deleted; the strap's are shown as recorded.
 */
@Composable
fun SessionScreen(api: ApiClient, initial: JSONObject, onGone: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    var points by remember { mutableStateOf<List<Point>?>(null) }
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    val haptics = LocalHapticFeedback.current
    val c = LocalMetricColors.current
    val start = s.getLong("start")
    val end = s.getLong("end")
    val own = s.getString("source") != "strap"
    LaunchedEffect(start, end) {
        points = try {
            // The server cuts days in its owner's timezone, which needn't be the phone's: a session
            // near midnight can sit in either neighbour, so read all three and keep the window.
            val day = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
            listOf(day.minusDays(1), day, day.plusDays(1)).flatMap { d ->
                val arr = api.daySeries(d, "hr").getJSONObject("series").getJSONArray("hr")
                List(arr.length()) { arr.getJSONArray(it).let { p -> Point(p.getLong(0), p.getDouble(1)) } }
            }.filter { it.t in start until end }.distinctBy { it.t }.sortedBy { it.t }
        } catch (_: ApiException) {
            emptyList()
        }
    }
    val stats = s.optJSONObject("stats")
    val load = stats?.optJSONObject("load")?.takeIf { !it.has("withheld") }
    val hr = stats?.optJSONObject("hr")?.takeIf { !it.has("withheld") }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SideStat(hr?.getInt("avg")?.toString() ?: "—", "avg bpm", null, Modifier.weight(1f))
                val strain = load?.takeIf { !it.isNull("strain") }?.getDouble("strain")
                val learning = load?.optJSONObject("strain_learning")
                if (learning != null) {
                    GaugeBlock("${learning.getInt("have")}/${learning.getInt("need")}", "days", learning.getInt("have") / learning.getInt("need").toFloat(),
                        c.strain.copy(alpha = 0.45f), "Strain", Note("learning your scale", MaterialTheme.colorScheme.onSurfaceVariant), 150.dp, 36.sp)
                } else {
                    GaugeBlock(strain?.let { "%.1f".format(it) } ?: "—", "of 21", strain?.let { (it / 21).toFloat() }, c.strain, "Strain", null, 150.dp, 36.sp)
                }
                SideStat(hr?.getInt("peak")?.toString() ?: "—", "peak bpm", null, Modifier.weight(1f))
            }
        }
        item {
            RidgeCard {
                CardHeader("Heart rate", "${clockOf(start)}–${clockOf(end)} · ${durationLabel(end - start)}")
                val p = points
                when {
                    p == null -> Subtle("Loading…")
                    p.isEmpty() -> Subtle(stats?.optJSONObject("hr")?.optJSONObject("withheld")?.getString("message") ?: "No heart rate in this window.")
                    else -> DayLineChart(p, start, c.heart, 3 * 60_000L, "bpm", height = 180.dp, spanMs = end - start,
                        axis = listOf(0, 1, 2, 3, 4).map { clockOf(start + it * (end - start) / 4) })
                }
            }
        }
        item { Zones(load, stats?.optJSONObject("load")?.optJSONObject("withheld")?.getString("message")) }
        item { Recovery(stats?.optJSONObject("hrr")) }
        item {
            SectionLabel(if (own) "Sport" else "Recorded by the strap")
            if (own) {
                Column(Modifier.padding(top = 8.dp)) {
                    SportGrid(Sport.entries, Sport.of(s.getString("sport"))) { pick ->
                        scope.launch {
                            try {
                                s = api.updateSession(s.getString("id"), JSONObject().put("sport", pick.key))
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            } catch (e: ApiException) {
                                snack(e.message ?: "Could not save.", null, null)
                            }
                        }
                    }
                }
            } else {
                Subtle("The strap's own sport codes aren't mapped yet, so it shows as Other.", Modifier.padding(top = 4.dp))
            }
        }
        if (own) item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            api.deleteSession(s.getString("id"))
                            snack("Workout deleted", null, null)
                            onGone()
                        } catch (e: ApiException) {
                            snack(e.message ?: "Could not delete.", null, null)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Outlined.Delete, null)
                Text("  Delete workout")
            }
        }
    }
}

@Composable
private fun Zones(load: JSONObject?, withheld: String?) {
    val c = LocalMetricColors.current
    RidgeCard {
        CardHeader("Time in zones", load?.let { "max ${it.getInt("hrmax")} · rest ${it.getInt("rhr")}" })
        val zones = load?.optJSONArray("zone_min")
        if (zones == null) {
            Subtle(withheld ?: "Not available.")
            return@RidgeCard
        }
        val max = (0 until zones.length()).maxOf { zones.getInt(it) }.coerceAtLeast(1)
        listOf("50–60%", "60–70%", "70–80%", "80–90%", "90%+").forEachIndexed { i, b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(64.dp)) {
                    Text("Zone ${i + 1}", style = RidgeType.caption.copy(fontWeight = RidgeType.label.fontWeight))
                    Text(b, style = RidgeType.change, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Bar(zones.getInt(i) / max.toFloat(), c.heart.copy(alpha = 0.45f + 0.137f * i), Modifier.weight(1f), height = 8.dp)
                Text("${zones.getInt(i)} min", style = RidgeType.label, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
            }
        }
    }
}

/** HR recovery as your own number, never against a population cut-off (SPEC S3). */
@Composable
private fun Recovery(hrr: JSONObject?) {
    RidgeCard(spacing = 6.dp) {
        CardHeader("Heart-rate recovery")
        if (hrr == null) {
            Subtle("Needs heart rate in the two minutes after you stop. It comes with the next sync if the strap stayed on.")
            return@RidgeCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("−${hrr.getInt("hrr1")}", style = RidgeType.cardNumber)
            Text(" bpm in the first minute", style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (!hrr.isNull("hrr2")) Subtle("−${hrr.getInt("hrr2")} bpm after two minutes. A faster drop over the months is fitter; compare with your own sessions of the same sport.")
    }
}
