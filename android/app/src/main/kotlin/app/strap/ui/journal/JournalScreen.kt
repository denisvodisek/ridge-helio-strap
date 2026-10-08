package app.strap.ui.journal

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.CardHeader
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.ListRow
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.today.journalEffect
import app.strap.ui.today.journalHeadline
import app.strap.ui.today.journalIcon
import app.strap.ui.today.journalName
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class Quick(val label: String, val amount: String, val kind: String, val value: Double, val name: String?, val logged: String)

private data class SuppDraft(val id: String?, val name: String, val amount: String, val unit: String, val notes: String)

private val QUICK = listOf(
    Quick("Espresso", "60 mg", "caffeine", 60.0, "espresso", "Espresso logged"),
    Quick("Coffee", "95 mg", "caffeine", 95.0, "coffee", "Coffee logged"),
    Quick("Tea", "45 mg", "caffeine", 45.0, "tea", "Tea logged"),
    Quick("Drink", "1 standard", "alcohol", 1.0, null, "Drink logged"),
)

private val FALLBACK_AMOUNTS = listOf(100, 200, 300, 400, 500, 750, 1000)

private val FALLBACK_SUPPLEMENTS = listOf(
    "Magnesium" to "mg", "D3" to "IU", "K" to "mcg", "B12" to "mcg", "Biotin" to "mcg",
    "Zinc" to "mg", "Vitamin C" to "mg", "Collagen" to "g", "Omega-3" to "mg",
)

private val UNITS = listOf("mg", "mcg", "g", "IU")

@Composable
fun JournalScreen(api: ApiClient) {
    var entries by remember { mutableStateOf<List<JSONObject>?>(null) }
    var hydration by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var weightDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<SuppDraft?>(null) }
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    val haptics = LocalHapticFeedback.current
    val today = LocalDate.now()
    val locate = rememberLocate(
        onLocation = { lat, lon ->
            scope.launch {
                try {
                    hydration = api.setHome(today, lat, lon)
                    snack("Home area set", null, null)
                } catch (e: ApiException) {
                    snack(e.message ?: "Could not save the home area.", null, null)
                }
            }
        },
        onFail = { snack(it, null, null) },
    )
    LaunchedEffect(reload) {
        try {
            val arr = api.journal(today.minusDays(6), today)
            entries = List(arr.length()) { arr.getJSONObject(it) }.sortedByDescending { it.getLong("ts") }
        } catch (e: ApiException) {
            snack(e.message ?: "Could not load the journal.", null, null)
        }
        try {
            hydration = api.hydration(today)
        } catch (e: ApiException) {
            snack(e.message ?: "Could not load water.", null, null)
        }
    }
    fun add(
        kind: String,
        amount: Double,
        name: String?,
        ts: String = OffsetDateTime.now().toString(),
        notes: String? = null,
        unit: String? = null,
        done: (JSONObject) -> String,
    ) = scope.launch {
        try {
            val body = JSONObject().put("kind", kind).put("amount", amount).put("ts", ts)
            name?.let { body.put("name", it) }
            notes?.let { body.put("notes", it) }
            unit?.let { body.put("unit", it) }
            snack(done(api.addJournal(body)), null, null)
            haptics.performHapticFeedback(HapticFeedbackType.Confirm) // one clear tick: it's logged
            reload++
        } catch (e: ApiException) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            snack(e.message ?: "Could not save.", null, null)
        }
    }
    fun delete(e: JSONObject) = scope.launch {
        try {
            api.deleteJournal(e.getString("id"))
            reload++
            snack("Entry deleted", "Undo") {
                val ts = Instant.ofEpochMilli(e.getLong("ts")).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                add(
                    e.getString("kind"), e.getDouble("amount"), e.optString("name").takeIf { it.isNotEmpty() && it != "null" }, ts,
                    journalEffect(e), e.optString("unit").takeIf { e.getString("kind") == "supplement" && it.isNotEmpty() },
                ) { "Entry restored" }
            }
        } catch (x: ApiException) {
            snack(x.message ?: "Could not delete.", null, null)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { DayLabel("Water") }
            item {
                WaterCard(hydration, onAmount = { ml -> add("water", ml.toDouble(), null) { "Water logged" } }, onLocate = locate, onForget = {
                    scope.launch {
                        try {
                            hydration = api.clearHome(today)
                        } catch (e: ApiException) {
                            snack(e.message ?: "Could not forget the home area.", null, null)
                        }
                    }
                })
            }
            item { DayLabel("Supplements") }
            item {
                SupplementCard(hydration, onPick = { draft = it }, onAdd = { draft = SuppDraft(null, "", "", "mg", "") })
            }
            item { DayLabel("Quick log") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { q -> QuickTile(q, Modifier.weight(1f)) { add(q.kind, q.value, q.name) { q.logged } } }
                        }
                    }
                }
            }
            val list = entries
            if (list != null && list.isEmpty()) item { Subtle("Nothing logged in the last 7 days.", Modifier.padding(4.dp)) }
            list?.groupBy { Instant.ofEpochMilli(it.getLong("ts")).atZone(ZoneId.systemDefault()).toLocalDate() }?.forEach { (day, items) ->
                item(key = day.toString()) { DayLabel(dayTitle(day, today)) }
                item(key = "$day-items") {
                    Grouped(items) { e, shape ->
                        val clock = clockOf(e.getLong("ts"))
                        val sub = when (e.getString("kind")) {
                            "supplement" -> listOfNotNull(journalEffect(e), clock).joinToString(" · ")
                            else -> listOfNotNull(journalName(e), clock).joinToString(" · ")
                        }
                        ListRow(
                            shape, journalHeadline(e), sub.ifEmpty { null },
                            onClick = if (e.getString("kind") == "supplement") {
                                { draft = draftOf(e) }
                            } else {
                                null
                            },
                            leading = { IconCircle(journalIcon(e), LocalRidgeColors.current.surface3, MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailing = {
                                IconButton(onClick = { delete(e) }) {
                                    Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { weightDialog = true },
            icon = { Icon(Icons.Rounded.MonitorWeight, null) },
            text = { Text("Weight", style = RidgeType.cardTitle) },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).height(64.dp),
        )
    }
    if (weightDialog) WeightDialog(onDismiss = { weightDialog = false }) { kg ->
        weightDialog = false
        add("weight", kg, null) { "Weight saved — ${it.getInt("rederived_days")} days recalculated." }
    }
    draft?.let { current ->
        SupplementSheet(current, onDismiss = { draft = null }, onChange = { draft = it }) { saving ->
            draft = null
            if (saving.id == null) {
                add("supplement", saving.amountValue, saving.name.trim(), notes = saving.notes.trim().ifEmpty { null }, unit = saving.unit) { "Supplement logged" }
            } else scope.launch {
                try {
                    val body = JSONObject().put("name", saving.name.trim()).put("amount", saving.amountValue).put("unit", saving.unit)
                    saving.notes.trim().takeIf { it.isNotEmpty() }?.let { body.put("notes", it) }
                    api.updateJournal(saving.id, body)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    snack("Supplement updated", null, null)
                    reload++
                } catch (e: ApiException) {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    snack(e.message ?: "Could not save.", null, null)
                }
            }
        }
    }
}

private val SuppDraft.amountValue: Double get() = amount.replace(',', '.').toDouble()

private fun draftOf(e: JSONObject) = SuppDraft(
    e.getString("id"),
    e.optString("name"),
    amountText(e.getDouble("amount")),
    e.optString("unit").ifEmpty { "mg" },
    journalEffect(e) ?: "",
)

private fun draftOfChip(s: JSONObject) = SuppDraft(
    null,
    s.getString("name"),
    if (s.isNull("last_amount")) "" else amountText(s.getDouble("last_amount")),
    s.getString("unit"),
    "",
)

private fun amountText(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

private fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

@Composable
private fun DayLabel(text: String) {
    Text(text, style = RidgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaterCard(hydration: JSONObject?, onAmount: (Int) -> Unit, onLocate: () -> Unit, onForget: () -> Unit) {
    val reminder = hydration?.optJSONObject("reminder")
    val amounts = hydration?.optJSONArray("amounts_ml")?.ints() ?: FALLBACK_AMOUNTS
    RidgeCard {
        CardHeader("Water", "logged")
        if (hydration == null) {
            Subtle("The total didn't load. The amounts below still log.")
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${hydration.getInt("today_ml")}", style = RidgeType.cardNumber)
                Text(" ml today", style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            }
            hydration.optJSONObject("average")?.optString("line")?.takeIf { it.isNotEmpty() }?.let { Subtle(it) }
            reminder?.takeIf { it.optBoolean("show") }?.jsonText("text")?.let { Reminder(it) }
            reminder?.jsonText("heat_note")?.let { Subtle(it) }
            if (hydration.optBoolean("home_set")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Subtle("Home area set", Modifier.weight(1f))
                    TextButton(onClick = onLocate) { Text("Update") }
                    TextButton(onClick = onForget) { Text("Forget") }
                }
            } else {
                TextButton(onClick = onLocate, modifier = Modifier.padding(start = 0.dp)) { Text("Use this phone for heat") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            amounts.forEach { ml -> ActionChip(if (ml == 1000) "1 L" else "$ml ml") { onAmount(ml) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplementCard(hydration: JSONObject?, onPick: (SuppDraft) -> Unit, onAdd: () -> Unit) {
    val chips = hydration?.optJSONArray("supplements")
    RidgeCard {
        CardHeader("Supplements")
        Subtle("Dose, and any effect you noticed. The name can be changed.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (chips == null) {
                FALLBACK_SUPPLEMENTS.forEach { (name, unit) ->
                    ActionChip(name) { onPick(SuppDraft(null, name, "", unit, "")) }
                }
            } else {
                for (i in 0 until chips.length()) ActionChip(chips.getJSONObject(i).getString("name")) { onPick(draftOfChip(chips.getJSONObject(i))) }
            }
            ActionChip("Add") { onAdd() }
        }
    }
}

@Composable
private fun Reminder(text: String) {
    val tone = LocalMetricColors.current.stressTone
    Text(
        text,
        style = RidgeType.body,
        color = tone.onContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.container).padding(12.dp),
    )
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(LocalRidgeColors.current.surface3).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = RidgeType.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun QuickTile(q: Quick, modifier: Modifier, onClick: () -> Unit) {
    val tone = LocalMetricColors.current.stressTone
    val icon: ImageVector = journalIcon(JSONObject().put("kind", q.kind).put("name", q.name ?: ""))
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(tone.container).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tone.onContainer)
        Column {
            Text(q.label, style = RidgeType.cardTitle, color = tone.onContainer)
            Text(q.amount, style = RidgeType.body, color = tone.onContainer.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun WeightDialog(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    val kg = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalRidgeColors.current.surface3,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Weight") },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text("kg") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(enabled = kg != null, onClick = { kg?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupplementSheet(draft: SuppDraft, onDismiss: () -> Unit, onChange: (SuppDraft) -> Unit, onSave: (SuppDraft) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val amount = draft.amount.replace(',', '.').toDoubleOrNull()
    val valid = draft.name.isNotBlank() && amount != null && amount > 0
    val units = if (draft.unit in UNITS) UNITS else listOf(draft.unit) + UNITS
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = LocalRidgeColors.current.surface1,
        scrimColor = Color.Black.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(vertical = 16.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (draft.id == null) "Log a supplement" else "Edit supplement", style = RidgeType.sheetTitle)
            OutlinedTextField(
                draft.name, { onChange(draft.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                draft.amount, { onChange(draft.copy(amount = it)) }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            ConnectedButtons(units, draft.unit, { it }) { onChange(draft.copy(unit = it)) }
            OutlinedTextField(
                draft.notes, { onChange(draft.copy(notes = it)) }, label = { Text("Effect, if you noticed one") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }) { Text("Cancel") }
                Button(
                    enabled = valid,
                    onClick = { scope.launch { state.hide() }.invokeOnCompletion { onSave(draft) } },
                    modifier = Modifier.height(48.dp),
                ) { Text(if (draft.id == null) "Log" else "Save", style = RidgeType.label) }
            }
        }
    }
}

/** Coarse location, and only after a tap. The coordinates go to the server and are not shown. */
@Composable
private fun rememberLocate(onLocation: (Double, Double) -> Unit, onFail: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    fun fetch() = readCoarseLocation(context, onLocation, onFail)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetch() else onFail("Heat stays off without location.")
    }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) fetch() else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

@SuppressLint("MissingPermission")
private fun readCoarseLocation(context: android.content.Context, onLocation: (Double, Double) -> Unit, onFail: (String) -> Unit) {
    val lm = context.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (provider == null) {
        onFail("This phone has no location source on.")
        return
    }
    val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    if (last != null) {
        onLocation(last.latitude, last.longitude)
        return
    }
    runCatching {
        lm.getCurrentLocation(provider, CancellationSignal(), context.mainExecutor) { loc ->
            if (loc != null) onLocation(loc.latitude, loc.longitude) else onFail("No location from this phone.")
        }
    }.onFailure { onFail("No location from this phone.") }
}

private fun JSONObject.jsonText(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

private fun JSONArray.ints(): List<Int> = List(length()) { getInt(it) }
