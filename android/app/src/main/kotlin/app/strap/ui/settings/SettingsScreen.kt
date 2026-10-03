package app.strap.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.BuildConfig
import app.strap.StrapApp
import app.strap.sync.SyncService
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.ListRow
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeIcons
import app.strap.ui.theme.RidgeTheme
import app.strap.ui.theme.RidgeType
import app.strap.ui.theme.themeSwatch
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SOURCE = "https://github.com/TheCommishDeuce/ridge-helio-strap"

private data class Item(val icon: ImageVector, val title: String, val detail: String, val external: Boolean = false, val onClick: () -> Unit)

/** The gear (D27): what is set up, a way to change it, and the raw diagnostics only under Advanced. */
@Composable
fun SettingsScreen(app: StrapApp, onProfile: () -> Unit, onChangeStrap: () -> Unit, onChangeServer: () -> Unit, onDiagnostics: () -> Unit) {
    val links = LocalUriHandler.current
    val sync by app.syncRunner.state.collectAsStateWithLifecycle()
    // Re-read when a sync changes state: it may have finished, or uploaded the outbox.
    val pairing = remember(sync) { app.vault.load() }
    val server = remember(sync) { app.vault.loadServer() }
    val last = remember(sync) { app.store.lastSync() }
    val waiting = remember(sync) { app.store.unpushedCounts().values.sum() }
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section("You", listOf(
            Item(RidgeIcons.profile, "Profile", "Date of birth, sex, height, activity level", onClick = onProfile),
        ))
        Text("Appearance", style = RidgeType.label, color = scheme.primary, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp))
        ThemePicker(app)
        Section("Strap", listOf(
            Item(RidgeIcons.strap, "Helio Strap", pairing?.mac ?: "Not paired", onClick = onChangeStrap),
            Item(RidgeIcons.sync, last?.let { "Last sync ${time(it.first)}" } ?: "Not synced yet",
                last?.let { it.second ?: "Complete" } ?: "Tap to sync now") { SyncService.start(app) },
        ))
        Section("Server", listOf(
            Item(if (waiting == 0) RidgeIcons.uploaded else RidgeIcons.uploading,
                server?.baseUrl?.let { runCatching { URI(it).host }.getOrNull() ?: it } ?: "Not connected",
                if (waiting == 0) "Everything uploaded" else "$waiting waiting to upload", onClick = onChangeServer),
        ))
        Section("About", listOf(
            Item(RidgeIcons.info, "Ridge ${BuildConfig.VERSION_NAME}", "Free software, AGPL-3.0. Source code and licences", external = true) { links.openUri(SOURCE) },
        ))
        Section("Advanced", listOf(
            Item(RidgeIcons.diagnostics, "Diagnostics", "What the strap delivered, sync details, the settings probe", onClick = onDiagnostics),
        ))
    }
}

@Composable
private fun Section(title: String, items: List<Item>) {
    val scheme = MaterialTheme.colorScheme
    Text(title, style = RidgeType.label, color = scheme.primary, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp))
    Grouped(items) { item, shape ->
        ListRow(shape, item.title, item.detail, onClick = item.onClick,
            leading = { IconCircle(item.icon, scheme.secondaryContainer, scheme.onSecondaryContainer) },
            trailing = {
                Icon(if (item.external) Icons.AutoMirrored.Rounded.OpenInNew else RidgeIcons.chevron, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            })
    }
}

private fun time(at: Instant): String {
    val t = at.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val clock = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> clock
        today.minusDays(1) -> "yesterday $clock"
        else -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
}

/** Themes as swatches you can see before you pick: background, card and a data colour each. */
@Composable
private fun ThemePicker(app: StrapApp) {
    val current by app.theme.theme.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RidgeTheme.entries.forEach { t ->
            val (bg, card, accent) = themeSwatch(t, dark)
            val on = t == current
            Column(
                Modifier.width(104.dp).clip(RoundedCornerShape(16.dp))
                    .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else LocalRidgeColors.current.hairline, RoundedCornerShape(16.dp))
                    .clickable { app.theme.set(t); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(bg).padding(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(card))
                    Box(Modifier.align(Alignment.BottomStart).size(12.dp).clip(CircleShape).background(accent))
                }
                Text(t.label, style = RidgeType.label)
                Text(t.blurb, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

