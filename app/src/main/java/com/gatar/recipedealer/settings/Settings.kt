package com.gatar.recipedealer.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Backend base URL, e.g. `https://recipes.example.org`; blank → local sender. */
    val baseUrl: String = "",
    /** Bearer token (decision D3); blank → no Authorization header. */
    val token: String = "",
    /** Share → toast + notification (default); off → the capture screen opens in the app. */
    val backgroundMode: Boolean = true,
    /** Send right after extraction instead of waiting for the user in the preview. */
    val autoSend: Boolean = false,
    /** Recipe detail as the kitchen-tablet layout (title + photo side by side, sections on ecru cards). */
    val tabletView: Boolean = false,
) {
    val hasBackend get() = baseUrl.isNotBlank()
}

/** Plain SharedPreferences (excluded from backups: allowBackup=false). */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current get() = _settings.value

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(current)
        prefs.edit()
            .putString(BASE_URL, s.baseUrl.trim().trimEnd('/'))
            .putString(TOKEN, s.token.trim())
            .putBoolean(BACKGROUND, s.backgroundMode)
            .putBoolean(AUTO_SEND, s.autoSend)
            .putBoolean(TABLET_VIEW, s.tabletView)
            .apply()
        _settings.value = read()
    }

    private fun read() = AppSettings(
        baseUrl = prefs.getString(BASE_URL, "").orEmpty(),
        token = prefs.getString(TOKEN, "").orEmpty(),
        backgroundMode = prefs.getBoolean(BACKGROUND, true),
        autoSend = prefs.getBoolean(AUTO_SEND, false),
        tabletView = prefs.getBoolean(TABLET_VIEW, false),
    )

    private companion object {
        const val BASE_URL = "baseUrl"
        const val TOKEN = "token"
        const val BACKGROUND = "backgroundMode"
        const val AUTO_SEND = "autoSend"
        const val TABLET_VIEW = "tabletView"
    }
}
