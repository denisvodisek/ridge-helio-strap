package app.strap.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The owner's theme choice, kept on the phone. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(prefs.getString("theme", null)?.let { n -> RidgeTheme.entries.firstOrNull { it.name == n } } ?: RidgeTheme.AUTO)
    val theme: StateFlow<RidgeTheme> = _theme.asStateFlow()

    fun set(theme: RidgeTheme) {
        prefs.edit().putString("theme", theme.name).apply()
        _theme.value = theme
    }
}
