package app.strap.ui.workout

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.ui.graphics.vector.ImageVector
import app.strap.ui.theme.RidgeIcons
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** The server's closed sport list (docs/denis/SPEC.md S3), with a name and an icon each. */
enum class Sport(val key: String, val label: String, val icon: ImageVector) {
    TENNIS("tennis", "Tennis", RidgeIcons.tennis),
    TREADMILL("treadmill", "Treadmill", RidgeIcons.treadmill),
    STAIRS("stairs", "Stairs", RidgeIcons.stairs),
    RUN("run", "Run", RidgeIcons.run),
    WALK("walk", "Walk", RidgeIcons.walk),
    RIDE("ride", "Ride", RidgeIcons.ride),
    GYM("gym", "Gym", RidgeIcons.gym),
    SWIM("swim", "Swim", RidgeIcons.swim),
    YOGA("yoga", "Yoga", RidgeIcons.yoga),
    OTHER("other", "Other", RidgeIcons.other),
    ;

    companion object {
        fun of(key: String?): Sport = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** A workout running right now: only what's needed to finish it, kept on the phone. */
data class Ongoing(val sport: Sport, val start: Instant)

/**
 * The phone's own workout memory: the session in progress (so a killed app or a reboot loses
 * nothing: it's just a start time) and the sports used most recently, which lead the picker.
 */
class WorkoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("workout", Context.MODE_PRIVATE)
    private val _ongoing = MutableStateFlow(read())
    val ongoing: StateFlow<Ongoing?> = _ongoing.asStateFlow()

    private fun read(): Ongoing? {
        val start = prefs.getLong("start", 0L).takeIf { it > 0 } ?: return null
        return Ongoing(Sport.of(prefs.getString("sport", null)), Instant.ofEpochMilli(start))
    }

    fun start(sport: Sport, at: Instant = Instant.now()) {
        prefs.edit().putString("sport", sport.key).putLong("start", at.toEpochMilli()).apply()
        remember(sport)
        _ongoing.value = Ongoing(sport, at)
    }

    fun clear() {
        prefs.edit().remove("sport").remove("start").apply()
        _ongoing.value = null
    }

    /** Sports in the order the picker shows them: most recently used first, then the rest. */
    fun recentFirst(): List<Sport> {
        val recent = prefs.getString("recent", "").orEmpty().split(',').mapNotNull { k -> Sport.entries.firstOrNull { it.key == k } }
        return recent + Sport.entries.filter { it !in recent }
    }

    fun remember(sport: Sport) {
        val order = (listOf(sport) + recentFirst().filter { it != sport }).take(4)
        prefs.edit().putString("recent", order.joinToString(",") { it.key }).apply()
    }
}

/** "52 min", "1 h 05 min". */
fun durationLabel(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min < 60) "$min min" else "%d h %02d min".format(min / 60, min % 60)
}

/** The line under a session in a list: duration, then strain or the reason there is none, then avg HR. */
fun sessionLine(s: JSONObject): String {
    val stats = s.optJSONObject("stats")
    val load = stats?.optJSONObject("load")
    val hr = stats?.optJSONObject("hr")
    return listOfNotNull(
        durationLabel(s.getLong("end") - s.getLong("start")),
        load?.takeIf { !it.isNull("strain") && it.has("strain") }?.let { "strain %.1f".format(it.getDouble("strain")) },
        hr?.takeIf { it.has("avg") }?.let { "avg ${it.getInt("avg")} bpm" },
    ).joinToString(" · ")
}
