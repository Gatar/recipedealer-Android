package com.gatar.recipedealer

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import com.gatar.recipedealer.capture.CaptureProcessor
import com.gatar.recipedealer.capture.CleanTextOnly
import com.gatar.recipedealer.capture.notSupported
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.settings.SettingsRepository
import com.gatar.recipedealer.store.CaptureStatus
import com.gatar.recipedealer.store.CaptureStore
import com.gatar.recipedealer.upload.CaptureUploadRunner
import com.gatar.recipedealer.upload.LocalCaptureSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class RecipeDealerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
    val processor = CaptureProcessor(
        context, store,
        mapOf(
            SourceType.TEXT to CleanTextOnly,
            SourceType.WEB to notSupported("web"),
            SourceType.IMAGE to notSupported("image"),
            SourceType.PDF to notSupported("pdf"),
        ),
    )
    private val uploadRunner = CaptureUploadRunner(store)

    init {
        processor.markInterrupted()
    }

    /** Queue a capture for sending. */
    fun send(id: String) {
        store.updateState(id) { it.copy(status = CaptureStatus.QUEUED, error = null) }
        appScope.launch { uploadRunner.upload(id, LocalCaptureSender()) }
    }
}
