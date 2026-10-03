package app.strap

import android.app.Application
import app.strap.pairing.KeyVault
import app.strap.store.LocalStore
import app.strap.sync.SyncRunner
import app.strap.ui.workout.WorkoutStore

/** Process-wide singletons. Small enough that a DI framework would be ceremony. */
class StrapApp : Application() {
    val store: LocalStore by lazy { LocalStore(this) }
    val vault: KeyVault by lazy { KeyVault(this) }
    val syncRunner: SyncRunner by lazy { SyncRunner(this, store, vault) }
    val workouts: WorkoutStore by lazy { WorkoutStore(this) }
}
