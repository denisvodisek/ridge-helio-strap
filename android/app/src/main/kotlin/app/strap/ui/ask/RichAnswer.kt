package app.strap.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.strap.ui.components.EvenAxis
import app.strap.ui.components.bar
import app.strap.ui.components.valueGrid
import app.strap.ui.components.valueTicks
import app.strap.ui.theme.GeistMono
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import org.json.JSONArray
import org.json.JSONObject

/** One piece of an answer, as the model wrote it (see the server's chat SYSTEM format). */
private sealed interface Block {
    data class Heading(val text: String) : Block
    data class Para(val text: String) : Block
    data class Bullet(val text: String, val marker: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    data class Stats(val cards: List<JSONObject>) : Block
    data class Chart(val spec: JSONObject) : Block
    data class Code(val text: String) : Block
}

/**
 * Parses the model's Markdown into blocks. Built for a stream: an unfinished fenced block
 * (stats or chart arriving token by token) is left out until its closing fence lands, so a
 * half-written card never flashes up as broken JSON.
 */
private fun parse(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = md.replace("\r", "").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val kind = t.removePrefix("```").trim().lowercase()
                val end = (i + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("```") } ?: return out // still streaming
                val body = lines.subList(i + 1, end).joinToString("\n")
                runCatching {
                    when (kind) {
                        "stats" -> JSONArray(body).let { a -> out += Block.Stats(List(minOf(a.length(), 4)) { a.getJSONObject(it) }) }
                        "chart" -> out += Block.Chart(JSONObject(body))
                        else -> out += Block.Code(body)
                    }
                }
                i = end + 1
                continue
            }
            t.startsWith("|") -> {
                flush()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (!cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }) rows += cells
                    i++
                }
                if (rows.isNotEmpty()) out += Block.Table(rows)
                continue
            }
            t.startsWith("#") -> { flush(); out += Block.Heading(t.trimStart('#').trim()) }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> { flush(); out += Block.Bullet(t.substring(2), "•") }
            Regex("""^\d+[.)] """).containsMatchIn(t) -> { flush(); out += Block.Bullet(t.substringAfter(' '), t.substringBefore(' ')) }
            t.isEmpty() -> flush()
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

/** **bold**, *italic* / _italic_ and `code` inline. */
@Composable
private fun inline(text: String): AnnotatedString {
    val code = LocalRidgeColors.current.surface3
    return remember(text, code) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("**", i) && text.indexOf("**", i + 2) > 0 -> {
                        val e = text.indexOf("**", i + 2)
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, e)) }
                        i = e + 2
                    }
                    text[i] == '`' && text.indexOf('`', i + 1) > 0 -> {
                        val e = text.indexOf('`', i + 1)
                        withStyle(SpanStyle(fontFamily = GeistMono, background = code)) { append(text.substring(i + 1, e)) }
                        i = e + 1
                    }
                    (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' && text.indexOf(text[i], i + 1) > 0 -> {
                        val e = text.indexOf(text[i], i + 1)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, e)) }
                        i = e + 1
                    }
                    else -> { append(text[i]); i++ }
                }
            }
        }
    }
}

/** An answer, drawn natively: text, tables, stat cards and small charts in the app's own style. */
@Composable
fun RichAnswer(markdown: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(markdown) { parse(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Heading -> Text(inline(b.text), style = RidgeType.cardTitle, color = color, modifier = Modifier.padding(top = 4.dp))
                is Block.Para -> Text(inline(b.text), style = RidgeType.paragraph, color = color)
                is Block.Bullet -> Row {
                    Text(b.marker, style = RidgeType.paragraph, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
                    Text(inline(b.text), style = RidgeType.paragraph, color = color)
                }
                is Block.Table -> AnswerTable(b.rows)
                is Block.Stats -> StatCards(b.cards)
                is Block.Chart -> AnswerChart(b.spec)
                is Block.Code -> Text(b.text, style = RidgeType.axis, color = color, modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(LocalRidgeColors.current.surface2).padding(12.dp))
            }
        }
    }
}

@Composable
private fun AnswerTable(rows: List<List<String>>) {
    val r = LocalRidgeColors.current
    val cols = rows.maxOf { it.size }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, r.hairline, RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { ri, row ->
            Row(Modifier.background(if (ri == 0) r.surface2 else Color.Transparent).padding(horizontal = 12.dp, vertical = 9.dp)) {
                (0 until cols).forEach { ci ->
                    Text(inline(row.getOrElse(ci) { "" }), style = if (ri == 0) RidgeType.caption.copy(fontWeight = FontWeight.Medium) else RidgeType.body,
                        color = if (ri == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.widthIn(min = if (ci == 0) 92.dp else 64.dp).padding(end = 12.dp))
                }
            }
        }
    }
}

/** Up to four cards, two to a row (fits a narrow cover screen): label, value, a coloured note. */
@Composable
private fun StatCards(cards: List<JSONObject>) {
    val r = LocalRidgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { c ->
                    val tone = when (c.optString("tone")) { "good" -> r.zoneGreen; "bad" -> r.zoneYellow; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(r.surface2).border(1.dp, r.hairline, RoundedCornerShape(16.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(c.optString("label").uppercase(), style = RidgeType.section, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(c.optString("value"), style = RidgeType.rowValue, maxLines = 1)
                        c.optString("note").takeIf { it.isNotBlank() }?.let { Text(it, style = RidgeType.change, color = tone, maxLines = 2) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A small bar or line chart from the model's points, styled like every other Ridge chart. */
@Composable
private fun AnswerChart(spec: JSONObject) {
    val pts = spec.optJSONArray("points") ?: return
    val labels = List(pts.length()) { pts.getJSONArray(it).optString(0) }
    val values = List(pts.length()) { pts.getJSONArray(it).optDouble(1) }.map { if (it.isNaN()) null else it }
    if (values.count { it != null } < 2) return
    val accent = LocalMetricColors.current.strain
    val grid = LocalRidgeColors.current.hairline
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val line = spec.optString("type") == "line"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalRidgeColors.current.surface2)
        .border(1.dp, grid, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(spec.optString("title") + spec.optString("unit").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
            style = RidgeType.caption.copy(fontWeight = FontWeight.Medium), color = faint)
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.fillMaxWidth().height(120.dp).drawWithCache {
            val vs = values.filterNotNull()
            val lo = if (line) vs.min() - (vs.max() - vs.min()) * 0.15 else 0.0
            val hi = vs.max() * 1.08 + if (vs.max() == lo) 1.0 else 0.0
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            val slot = size.width / values.size
            val ticks = valueTicks(measurer, lo, hi, faint, ::y)
            val path = Path()
            values.forEachIndexed { i, v -> v?.let { val x = slot * i + slot / 2; if (path.isEmpty) path.moveTo(x, y(it)) else path.lineTo(x, y(it)) } }
            onDrawBehind {
                valueGrid(ticks, grid)
                if (line) {
                    drawPath(path, accent.copy(alpha = 0.18f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(path, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    values.forEachIndexed { i, v -> v?.let { drawCircle(accent, 3.dp.toPx(), Offset(slot * i + slot / 2, y(it))) } }
                } else {
                    val w = minOf(slot * 0.62f, 26.dp.toPx())
                    values.forEachIndexed { i, v -> v?.let { bar(if (i == values.lastIndex) accent else accent.copy(alpha = 0.55f), slot * i + (slot - w) / 2, y(it), w, size.height) } }
                }
            }
        })
        val step = maxOf(1, (labels.size + 6) / 7) // at most ~7 labels
        EvenAxis(labels.filterIndexed { i, _ -> i % step == 0 }, slots = step == 1)
    }
}
