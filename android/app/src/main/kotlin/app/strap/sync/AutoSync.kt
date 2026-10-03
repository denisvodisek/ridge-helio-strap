package app.strap.sync

import android.Manifest
import android.content.pm.PackageManager
import app.strap.StrapApp
import app.strap.ui.isRunning
import java.time.Duration
import java.time.Instant

/**
 * Syncs when the app comes to the front (DD1, overriding upstream D19's manual-only sync), so
 * opening Ridge shows this morning's numbers without a pull. The cooldown counts from the last
 * attempt, failed ones included: switching apps back and forth, a screen rotation, or a strap
 * out of range must not set off a connect every time the activity starts.
 */
object AutoSync {
    val COOLDOWN: Duration = Duration.ofMinutes(15)

    fun onAppVisible(app: StrapApp, now: Instant = Instant.now()) {
        if (app.syncRunner.state.value.isRunning) return
        // Setup runs its own first sync; until both halves exist there is nothing to sync to.
        if (app.vault.load() == null || app.vault.loadServer() == null) return
        if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val last = app.store.lastSync()?.first
        if (last != null && Duration.between(last, now) < COOLDOWN) return
        SyncService.start(app)
    }
}
