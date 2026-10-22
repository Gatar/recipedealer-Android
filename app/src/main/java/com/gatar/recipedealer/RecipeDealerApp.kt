package com.gatar.recipedealer

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.capture.CaptureProcessor
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.recipes.ImageCache
import com.gatar.recipedealer.recipes.RecipeCache
import com.gatar.recipedealer.recipes.RecipeSync
import com.gatar.recipedealer.recipes.SyncWorker
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
    val recipeSync = RecipeSync(recipes, ::backend)
    val images = ImageCache(File(context.filesDir, "recipes/images"), ::backend)
    init {
        processor.markInterrupted()
    }

    /** Queue a capture for sending (backend from the settings, else the local sender). */
    fun send(id: String) = UploadQueue.enqueue(context, id)

    /** Client for the backend in the settings; null without one (local mode). */
    fun backend(): BackendClient? = settings.current.takeIf { it.hasBackend }?.let { BackendClient(it.baseUrl, it.token) }
}
