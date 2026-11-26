package com.gatar.recipedealer.settings

import android.content.Context
import com.gatar.recipedealer.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** The app's colors: SYSTEM is the original look (wallpaper colors, system light / dark); the others are fixed. */
enum class ColorTheme { SYSTEM, LIGHT, DARK, KITCHEN }

data class AppSettings(
    /** Backend base URL; always [SERVER_URL] in the app (blank → local sender, only in tests). */
    val baseUrl: String = "",
    /** Device token from login / registration (or an admin enrollment); blank → no Authorization header. Not shown. */
    val token: String = "",
    /** The logged-in user as the server spells it; blank for a token from an admin enrollment. */
    val userName: String = "",
    /** Device name sent with the login; the same name again keeps the server's device (and replaces its token). */
    val deviceName: String = "",
    /**
     * The household whose recipes the local cache holds; blank on installs from before households (all of them in the
     * default household). A login into another household empties the cache.
     */
    val householdId: String = "",
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
    /** Recipe list layout: 0 = list, 1–3 = grid of photo cards with that many columns (3 only with [tabletView]). */
    val listColumns: Int = 0,
    val colorTheme: ColorTheme = ColorTheme.SYSTEM,
    /** Uploads ask the server to translate a new recipe into the device language (header `Translate-To`). */
    val translateNew: Boolean = false,
) {
    val hasBackend get() = baseUrl.isNotBlank()

    /** Most grid columns this device offers: 3 only in the tablet view. */
    val maxListColumns get() = if (tabletView) 3 else 2

    /** [listColumns] limited to what the device offers (a tablet layout of 3 falls back to 2 without the tablet view). */
    val effectiveListColumns get() = listColumns.coerceIn(0, maxListColumns)

    /** The language a new recipe is translated into, or null when the setting is off. */
    val translateTo: String? get() = if (translateNew) deviceLanguage() else null

    /** No usable token: the app asks for a login. */
    val needsLogin get() = token.isBlank() || authRejected

    companion object {
        /** The server, fixed per release (a debug build may name a development server, see `app/build.gradle.kts`). */
        const val SERVER_URL = BuildConfig.SERVER_URL

        /** The household of every user from before households (backend V19). */
        const val DEFAULT_HOUSEHOLD_ID = "00000000-0000-7000-8000-000000000001"

        /** ISO 639 code of the device language, e.g. `pl`. */
        fun deviceLanguage(): String = Locale.getDefault().language

        /** The device language in itself, e.g. "polski". */
        fun deviceLanguageName(): String = Locale.getDefault().let { it.getDisplayLanguage(it) }
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
            .putString(TOKEN, s.token.trim())
            .putString(USER_NAME, s.userName)
            .putString(DEVICE_NAME, s.deviceName.trim())
            .putString(HOUSEHOLD_ID, s.householdId)
            .putBoolean(AUTH_REJECTED, s.authRejected)
            .putBoolean(BACKGROUND, s.backgroundMode)
            .putBoolean(AUTO_SEND, s.autoSend)
            .putBoolean(TABLET_VIEW, s.tabletView)
            .putBoolean(TAG_COLUMN, s.tagColumn)
            .putInt(LIST_COLUMNS, s.listColumns)
            .putString(COLOR_THEME, s.colorTheme.name)
            .putBoolean(TRANSLATE_NEW, s.translateNew)
            .apply()
        _settings.value = read()
    }

    private fun read() = AppSettings(
        // not a setting any more (user, 2026-11-25): the stored `baseUrl` of older versions is ignored
        baseUrl = AppSettings.SERVER_URL,
        token = prefs.getString(TOKEN, "").orEmpty(),
        userName = prefs.getString(USER_NAME, "").orEmpty(),
        deviceName = prefs.getString(DEVICE_NAME, "").orEmpty(),
        householdId = prefs.getString(HOUSEHOLD_ID, "").orEmpty(),
        authRejected = prefs.getBoolean(AUTH_REJECTED, false),
        backgroundMode = prefs.getBoolean(BACKGROUND, true),
        autoSend = prefs.getBoolean(AUTO_SEND, false),
        tabletView = prefs.getBoolean(TABLET_VIEW, false),
        tagColumn = prefs.getBoolean(TAG_COLUMN, true),
        listColumns = prefs.getInt(LIST_COLUMNS, 0),
        colorTheme = ColorTheme.entries.firstOrNull { it.name == prefs.getString(COLOR_THEME, null) } ?: ColorTheme.SYSTEM,
        translateNew = prefs.getBoolean(TRANSLATE_NEW, false),
    )

    private companion object {
        const val TOKEN = "token"
        const val USER_NAME = "userName"
        const val DEVICE_NAME = "deviceName"
        const val HOUSEHOLD_ID = "householdId"
        const val AUTH_REJECTED = "authRejected"
        const val BACKGROUND = "backgroundMode"
        const val AUTO_SEND = "autoSend"
        const val TABLET_VIEW = "tabletView"
        const val TAG_COLUMN = "tagColumn"
        const val LIST_COLUMNS = "listColumns"
        const val COLOR_THEME = "colorTheme"
        const val TRANSLATE_NEW = "translateNew"
    }
}
