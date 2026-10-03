package app.strap.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.Loading
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.ListRow
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.StepProgress
import app.strap.ui.components.Subtle
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Jurca 2005's self-reported activity categories: a short name, then the question's own wording. */
private val SRPA = listOf(
    "Inactive" to "Avoid walking or exertion",
    "Light" to "Walk for pleasure, little other activity",
    "Moderate" to "10–60 min a week of golf, gardening, weights…",
    "Active" to "Run, swim or cycle 1–3 h a week",
    "Very active" to "Run, swim or cycle over 3 h a week",
)

/** What the profile form holds; equal to the server's copy until something is edited. */
private data class Form(val dob: LocalDate?, val sex: String?, val height: String, val srpa: Int?) {
    val heightCm: Double? get() = height.replace(',', '.').toDoubleOrNull()
    val heightOk: Boolean get() = height.isBlank() || heightCm?.let { it in 100.0..250.0 } == true
    val valid: Boolean get() = heightOk && (dob == null || !dob.isAfter(LocalDate.now().minusYears(13)))

    /** The four fields that are set, for the completeness bar. */
    val filled: Int get() = listOf(dob != null, sex != null, heightCm != null, srpa != null).count { it }

    fun json(): JSONObject = JSONObject()
        .put("height_cm", heightCm ?: JSONObject.NULL)
        .put("sex", sex ?: JSONObject.NULL)
        .put("dob", dob?.toString() ?: JSONObject.NULL)
        .put("srpa", srpa ?: JSONObject.NULL)

    companion object {
        fun of(o: JSONObject) = Form(
            dob = if (o.isNull("dob")) null else LocalDate.parse(o.getString("dob")),
            sex = if (o.isNull("sex")) null else o.getString("sex"),
            height = if (o.isNull("height_cm")) "" else o.getDouble("height_cm").let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() },
            srpa = if (o.isNull("srpa")) null else o.getInt("srpa"),
        )
    }
}

/** Settings → Profile: the inputs every body-based number needs. Saving re-derives every day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(api: ApiClient) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    val haptics = LocalHapticFeedback.current
    var loaded by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Form?>(null) }
    var form by remember { mutableStateOf<Form?>(null) }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val o = api.profile()
            loaded = o
            saved = Form.of(o)
            form = saved
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val f = form ?: return Loading(error)
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Completeness first: what's still missing is the one thing worth knowing on arrival.
        val weightSet = loaded?.optJSONObject("latest_weight") != null
        val done = f.filled + if (weightSet) 1 else 0
        RidgeCard(spacing = 8.dp) {
            Text(if (done == 5) "Your profile is complete" else "${5 - done} of 5 still to set", style = RidgeType.cardTitle)
            StepProgress(done, 5, LocalMetricColors.current.recovery)
            Subtle("Calories, strain, sleep need and VO₂max use these. Change one and every past day is recalculated.")
        }
        Label("Date of birth", "your age and sleep need")
        Grouped(listOf(Unit)) { _, shape ->
            ListRow(shape, f.dob?.format(DateTimeFormatter.ofPattern("d MMMM yyyy")) ?: "Not set",
                f.dob?.let { "${Period.between(it, LocalDate.now()).years} years old" } ?: "Sets your age and your sleep need",
                onClick = { picking = true },
                leading = { IconCircle(Icons.Outlined.Cake, scheme.secondaryContainer, scheme.onSecondaryContainer) })
        }
        Label("Sex", "metabolic rate, VO₂max")
        ConnectedButtons(listOf<String?>("male", "female"), f.sex, { if (it == "male") "Male" else "Female" }) { form = f.copy(sex = it) }
        Subtle("Sex at birth: the calorie and VO₂max formulas are built on it.", Modifier.padding(start = 4.dp))
        Label("Height", "BMI, metabolic rate, stride")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A stepper beside the field: most edits are a centimetre or two, not a retype.
            fun nudge(by: Int) {
                val cm = (f.heightCm ?: 170.0).roundToInt() + by
                form = f.copy(height = cm.coerceIn(100, 250).toString())
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            FilledTonalIconButton(onClick = { nudge(-1) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Remove, "One centimetre less") }
            OutlinedTextField(f.height, { form = f.copy(height = it) }, label = { Text("cm") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = !f.heightOk, textStyle = RidgeType.rowValue.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FilledTonalIconButton(onClick = { nudge(1) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Add, "One centimetre more") }
        }
        Label("Activity level", "the VO₂max estimate")
        Grouped(SRPA.indices.toList()) { i, shape ->
            ListRow(shape, SRPA[i].first, SRPA[i].second, onClick = { form = f.copy(srpa = i) },
                leading = { RadioButton(selected = f.srpa == i, onClick = null) })
        }
        Subtle("Your usual week, not this one.", Modifier.padding(start = 4.dp))
        Label("Weight", "energy and VO₂max")
        val weight = loaded?.optJSONObject("latest_weight")
        Grouped(listOf(Unit)) { _, shape ->
            ListRow(shape, weight?.let { "%.1f kg".format(it.getDouble("kg")) } ?: "No weight logged",
                weight?.let { "Logged " + Instant.ofEpochMilli(it.getLong("ts")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy")) + " · log a new one in Journal" }
                    ?: "Log one in Journal: energy and VO₂max need it",
                leading = { IconCircle(Icons.Rounded.MonitorWeight, scheme.secondaryContainer, scheme.onSecondaryContainer) })
        }
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    try {
                        val out = api.saveProfile(f.json())
                        loaded = out
                        saved = Form.of(out)
                        form = saved
                        val days = out.getInt("rederived_days")
                        snack(if (days > 0) "Profile saved · $days days recalculated" else "Profile saved", null, null)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    } catch (e: ApiException) {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        snack(e.message ?: "Could not save.", null, null)
                    }
                    saving = false
                }
            },
            enabled = f != saved && f.valid && !saving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp), shape = MaterialTheme.shapes.medium,
        ) { Text(if (saving) "Saving and recalculating…" else "Save", style = RidgeType.cardTitle) }
    }
    if (picking) {
        // The picker speaks UTC midnight; a date of birth is a calendar date, so convert at UTC both ways.
        // Typed, not a calendar: a birth date is decades back, and paging there is a chore (M3 pickers guidance).
        val state = rememberDatePickerState(
            initialSelectedDateMillis = f.dob?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = 1900..LocalDate.now().year - 13,
            initialDisplayMode = DisplayMode.Input,
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { form = f.copy(dob = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = LocalRidgeColors.current.surface3),
        ) { DatePicker(state) }
    }
}

/** A field's name, and on the right what it unlocks: the reason to fill it in, said where it's asked. */
@Composable
private fun Label(text: String, unlocks: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = RidgeType.label, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text("for $unlocks", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
