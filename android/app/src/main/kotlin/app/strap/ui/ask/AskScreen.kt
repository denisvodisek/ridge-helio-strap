package app.strap.ui.ask

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeIcons
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One turn. [tools] are the data the answer looked at, shown small under it, so it's checkable;
 * [status] is what the model is doing while the reply hasn't started ("Reading your history…").
 */
private data class Turn(val role: String, val text: String, val tools: List<String> = emptyList(), val failed: Boolean = false,
                        val status: String? = null, val done: Boolean = true)

/** Questions that show what this can do on day one; tapping one asks it. */
private val STARTERS = listOf(
    "How did I sleep this week?",
    "What's pulling my recovery down?",
    "How do my tennis sessions compare?",
    "Does a late coffee change my sleep?",
)

/** Plain names for the server's tools, for the "looked at" line. */
private val TOOL_NAMES = mapOf(
    "metrics" to "what's recorded", "day_summary" to "a day's summary", "daily" to "daily history", "samples" to "raw readings",
    "workout_sessions" to "workouts", "journal_entries" to "journal", "journal_hydration" to "water",
    "owner_profile" to "profile", "query" to "a database query",
)

/**
 * Ask your data (DD2): you type, the server's model looks things up with read-only tools and
 * answers from what it found. Kept to one conversation in memory: nothing is stored.
 */
@Composable
fun AskScreen(api: ApiClient) {
    val turns = remember { mutableStateListOf<Turn>() }
    var draft by rememberSaveable { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    // Follow the reply as it grows (its length changes on every streamed piece).
    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || thinking) return
        turns.add(Turn("user", q))
        draft = ""
        thinking = true
        scope.launch {
            val history = JSONArray().apply { turns.filter { !it.failed }.takeLast(20).forEach { put(JSONObject().put("role", it.role).put("content", it.text)) } }
            // The reply grows in place as pieces arrive: one turn, replaced on every event.
            turns.add(Turn("assistant", "", status = "Thinking…", done = false))
            val at = turns.lastIndex
            try {
                api.chatStream(history).collect { e ->
                    val t = turns[at]
                    turns[at] = when (e.optString("type")) {
                        "status" -> t.copy(status = e.optString("text"))
                        "delta" -> t.copy(text = t.text + e.optString("text"), status = null)
                        "done" -> t.copy(done = true, status = null, tools = e.optJSONArray("tools")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty().distinct(),
                            text = t.text.ifBlank { "No answer came back. Try asking another way." })
                        "error" -> t.copy(text = e.optString("message"), failed = true, done = true, status = null)
                        else -> t
                    }
                }
            } catch (e: ApiException) {
                turns[at] = turns[at].copy(text = e.message ?: "That didn't work.", failed = true, done = true, status = null)
            }
            if (!turns[at].done) turns[at] = turns[at].copy(done = true, status = null)
            thinking = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (turns.isEmpty()) item { Starters(::ask) }
            items(turns) { t -> if (t.role == "user") Question(t.text) else Answer(t) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                draft, { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask about your sleep, recovery, workouts…") },
                shape = RoundedCornerShape(24.dp), maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { ask(draft) }),
            )
            FilledIconButton(onClick = { ask(draft) }, enabled = draft.isNotBlank() && !thinking, modifier = Modifier.padding(start = 8.dp).size(52.dp)) {
                Icon(RidgeIcons.send, "Send")
            }
        }
    }
}

@Composable
private fun Starters(onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text("Ask about your data", style = RidgeType.sheetTitle)
        Text("Answers come from your own numbers. Each question sends the model only the data it needs.",
            style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        STARTERS.forEach { q ->
            Text(q, style = RidgeType.rowTitle, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(LocalRidgeColors.current.card).clickable { onAsk(q) }.padding(horizontal = 16.dp, vertical = 14.dp))
        }
    }
}

/** Your question: a soft bubble on the right. */
@Composable
private fun Question(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(text, style = RidgeType.paragraph, color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** The answer: rendered Markdown, cards and charts on the page, with what it looked at underneath. */
@Composable
private fun Answer(t: Turn) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t.text.isEmpty() && !t.done) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Thinking()
                t.status?.let { Text(it, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else if (t.failed) {
            Text(t.text, style = RidgeType.paragraph, color = MaterialTheme.colorScheme.error)
        } else {
            RichAnswer(t.text)
            t.status?.let { Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (t.tools.isNotEmpty()) {
            Text("Based on " + t.tools.joinToString(", ") { TOOL_NAMES[it] ?: it }, style = RidgeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Three dots breathing in turn while the server works. */
@Composable
private fun Thinking() {
    val pulse = rememberInfiniteTransition(label = "thinking")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by pulse.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}
