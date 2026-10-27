package com.gatar.recipedealer.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Backend base URL, e.g. `https://recipes.example.org`; blank → local sender. */
    val baseUrl: String = "",
    /** Device token from login / registration (or an admin enrollment); blank → no Authorization header. Not shown. */
    val token: String = "",
    /** The logged-in user as the server spells it; blank for a token from an admin enrollment. */
    val userName: String = "",
    /** Device name sent with the login; the same name again keeps the server's device (and replaces its token). */
    val deviceName: String = "",
    /** The server rejected [token] (401): the login screen opens until a new login. */
    val authRejected: Boolean = false,
    /** Share → toast + notification (default); off → the capture screen opens in the app. */
    val backgroundMode: Boolean = true,
    /** Send right after extraction instead of waiting for the user in the preview. */
    val autoSend: Boolean = false,
    /** Recipe detail as the kitchen-tablet layout (title + photo side by side, sections on ecru cards). */
    val tabletView: Boolean = false,
    /** The tag column next to the recipe list. */
    val tagColumn: Boolean = true,
) {
    val hasBackend get() = baseUrl.isNotBlank()

    /** No usable token: the app asks for a login. */
    val needsLogin get() = token.isBlank() || authRejected

    companion object {
        /** The household's server; editable, and blank still means local mode. */
        const val DEFAULT_BASE_URL = "https://2.31.66.200"
    }
}

/** Plain SharedPreferences (excluded from backups: allowBackup=false). */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current get() = _settings.value

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(current)
        prefs.edit()
            .putString(BASE_URL, s.baseUrl.trim().trimEnd('/'))
            .putString(TOKEN, s.token.trim())
            .putString(USER_NAME, s.userName)
            .putString(DEVICE_NAME, s.deviceName.trim())
            .putBoolean(AUTH_REJECTED, s.authRejected)
            .putBoolean(BACKGROUND, s.backgroundMode)
            .putBoolean(AUTO_SEND, s.autoSend)
            .putBoolean(TABLET_VIEW, s.tabletView)
            .putBoolean(TAG_COLUMN, s.tagColumn)
            .apply()
        _settings.value = read()
    }

    private fun read() = AppSettings(
        // a fresh install talks to the household's server; a URL cleared by the user stays blank
        baseUrl = prefs.getString(BASE_URL, AppSettings.DEFAULT_BASE_URL).orEmpty(),
        token = prefs.getString(TOKEN, "").orEmpty(),
        userName = prefs.getString(USER_NAME, "").orEmpty(),
        deviceName = prefs.getString(DEVICE_NAME, "").orEmpty(),
        authRejected = prefs.getBoolean(AUTH_REJECTED, false),
        backgroundMode = prefs.getBoolean(BACKGROUND, true),
        autoSend = prefs.getBoolean(AUTO_SEND, false),
        tabletView = prefs.getBoolean(TABLET_VIEW, false),
        tagColumn = prefs.getBoolean(TAG_COLUMN, true),
    )

    private companion object {
        const val BASE_URL = "baseUrl"
        const val TOKEN = "token"
        const val USER_NAME = "userName"
        const val DEVICE_NAME = "deviceName"
        const val AUTH_REJECTED = "authRejected"
        const val BACKGROUND = "backgroundMode"
        const val AUTO_SEND = "autoSend"
        const val TABLET_VIEW = "tabletView"
        const val TAG_COLUMN = "tagColumn"
    }
}
