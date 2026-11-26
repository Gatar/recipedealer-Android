package com.gatar.recipedealer

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.Session
import com.gatar.recipedealer.api.TagCatalog
import com.gatar.recipedealer.settings.AppSettings
import com.gatar.recipedealer.capture.CaptureProcessor
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.recipes.ImageCache
import com.gatar.recipedealer.recipes.RecipeCache
import com.gatar.recipedealer.recipes.RecipeSync
import com.gatar.recipedealer.recipes.SyncWorker
import com.gatar.recipedealer.recipes.TagCatalogStore
import com.gatar.recipedealer.capture.ImageSourceExtractor
import com.gatar.recipedealer.capture.PdfSourceExtractor
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.ocr.OcrEngine
import com.gatar.recipedealer.web.ExtractorScripts
import com.gatar.recipedealer.web.TextSourceExtractor
import com.gatar.recipedealer.web.WebSourceExtractor
import com.gatar.recipedealer.settings.SettingsRepository
import com.gatar.recipedealer.store.CaptureStore
import com.gatar.recipedealer.upload.UploadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class RecipeDealerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Whole-page snapshots for the OCR fallback; must run before the first WebView is created.
        WebView.enableSlowWholeDocumentDraw()
        container = AppContainer(this)
        UploadQueue.resume(this, includeAuthFailures = false)
        SyncWorker.schedulePeriodic(this)
        Notifications.createChannels(this)
        // Debug builds: chrome://inspect / CDP via `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
    }
}

val Context.container get() = (applicationContext as RecipeDealerApp).container

class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val store = CaptureStore(File(context.filesDir, "captures"))
    val scripts = ExtractorScripts(context)
    val ocr = OcrEngine()
    val processor = CaptureProcessor(
        context, store,
        mapOf(
            SourceType.TEXT to TextSourceExtractor(context, scripts),
            SourceType.WEB to WebSourceExtractor(context, scripts, ocr),
            SourceType.IMAGE to ImageSourceExtractor(ocr),
            SourceType.PDF to PdfSourceExtractor(ocr),
        ),
    )
    val inbox = Inbox(store, ::backend)
    val recipes = RecipeCache(File(context.filesDir, "recipes/recipes.json"))
    val tags = TagCatalogStore(File(context.filesDir, "recipes/tags.json"))
    val recipeSync = RecipeSync(recipes, ::backend, tags = tags)
    val images = ImageCache(File(context.filesDir, "recipes/images"), ::backend)
    init {
        processor.markInterrupted()
    }

    /** Queue a capture for sending (backend from the settings, else the local sender). */
    fun send(id: String) = UploadQueue.enqueue(context, id)

    /** "Wyślij jako nowy": send, and decide NEW for every extracted recipe without asking. */
    fun sendAsNew(id: String) {
        store.updateState(id) { it.copy(asNew = true) }
        send(id)
    }

    /** Client for the backend in the settings; null without one (local mode). A dead token → login screen. */
    fun backend(): BackendClient? = settings.current.takeIf { it.hasBackend }?.let {
        BackendClient(it.baseUrl, it.token, onUnauthorized = { UploadQueue.pauseForAuth(context) })
    }

    /**
     * After login, a new household or joining one: the new token; the queue blocked by auth goes again. A session of
     * another household than the cached one empties the cache first (installs from before households cached the
     * default household).
     */
    fun signIn(deviceName: String, session: Session) {
        val cached = settings.current.householdId.ifBlank { AppSettings.DEFAULT_HOUSEHOLD_ID }
        val household = session.householdId ?: cached
        if (household != cached) forgetHouseholdData()
        settings.update {
            it.copy(token = session.token, userName = session.userName, deviceName = deviceName, householdId = household,
                authRejected = false)
        }
        Notifications.cancelAuth(context)
        UploadQueue.resume(context, includeAuthFailures = true)
        SyncWorker.syncNow(context)
    }

    /**
     * Forgets the token (the server keeps the device; the next login with the same device name reuses it).
     * [forgetData] after the account or the household was deleted: the cached recipes, tags and photos go too.
     */
    fun signOut(forgetData: Boolean = false) {
        if (forgetData) forgetHouseholdData()
        settings.update {
            it.copy(token = "", userName = "", authRejected = false, householdId = if (forgetData) "" else it.householdId)
        }
    }

    /** Recipes, tags, photos and waiting captures of the household; the device's own captures stay. */
    private fun forgetHouseholdData() {
        recipes.clear()
        tags.put(TagCatalog())
        images.clear()
        inbox.clear()
    }

    /** The device name from the system settings ("Galaxy Tab A8"), else the model. */
    fun defaultDeviceName(): String =
        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
}
