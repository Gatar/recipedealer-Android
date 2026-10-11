package com.gatar.recipedealer.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gatar.recipedealer.MainActivity
import com.gatar.recipedealer.R
import com.gatar.recipedealer.capture.CaptureJob
import com.gatar.recipedealer.capture.CaptureProcessor
import com.gatar.recipedealer.capture.CaptureService
import com.gatar.recipedealer.container
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.web.BrowserActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Translucent trampoline for ACTION_SEND / ACTION_SEND_MULTIPLE. Reads the share, copies shared files into app
 * storage (URI grants end with this activity), hands the capture to [CaptureService] and finishes – the user stays
 * in the source app. With background mode off, the capture screen opens in the app instead.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val request = readRequest(intent)
        val route = ShareRouter.route(request)
        if (route == null) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val job = container.processor.start(request, route)
            val copied = withContext(Dispatchers.IO) { copyFiles(route.files, File(job.filesDir, CaptureProcessor.INCOMING)) }
            val local = request.copy(files = copied)
            val localRoute = route.copy(files = copied)
            val background = container.settings.current.backgroundMode
            when {
                background -> {
                    CaptureService.start(this@ShareActivity, local, localRoute)
                    Toast.makeText(this@ShareActivity, R.string.share_toast, Toast.LENGTH_SHORT).show()
                }
                route.type == SourceType.WEB -> {
                    container.processor.saveJob(CaptureJob(local, localRoute, job.filesDir))
                    startActivity(BrowserActivity.intent(this@ShareActivity, request.captureId))
                }
                else -> {
                    CaptureService.start(this@ShareActivity, local, localRoute)
                    startActivity(MainActivity.openCaptureIntent(this@ShareActivity, request.captureId))
                }
            }
            finish()
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    private fun readRequest(intent: Intent): ShareRequest {
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            }
            if (ShareRouter.clipDataIsContent(intent.type, hasExtraStream = isNotEmpty())) {
                intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(::add) }
            }
        }.distinct()
        val ignoredPreviews = if (uris.isEmpty()) intent.clipData?.let { c -> (0 until c.itemCount).count { c.getItemAt(it).uri != null } } ?: 0 else 0
        val extras = intent.extras?.keySet()?.sorted()?.map { key ->
            @Suppress("DEPRECATION") val v = intent.extras?.get(key)
            "$key=${v?.javaClass?.simpleName ?: "null"}"
        }.orEmpty() + listOfNotNull(ignoredPreviews.takeIf { it > 0 }?.let { "clipData: $it preview URI(s) ignored" })
        return ShareRequest(
            captureId = UUID.randomUUID().toString(),
            capturedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            action = intent.action,
            mimeType = intent.type,
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString(),
            referrer = referrer?.takeIf { it.scheme == "android-app" }?.host,
            files = uris.map { SharedFile(it.toString(), contentResolver.getType(it) ?: intent.type, displayName(it)) },
            extras = extras,
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** Copies each file to [dir]; the returned entries point at the local copies. Unreadable files are dropped. */
    private fun copyFiles(files: List<SharedFile>, dir: File): List<SharedFile> {
        dir.mkdirs()
        return files.mapIndexedNotNull { i, f ->
            val target = File(dir, "%02d-%s".format(i, safeName(f.displayName ?: "file")))
            runCatching {
                contentResolver.openInputStream(Uri.parse(f.uri))!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                f.copy(uri = target.absolutePath)
            }.getOrNull()
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80)
}
