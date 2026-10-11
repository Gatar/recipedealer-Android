package com.gatar.recipedealer.capture

import android.content.Context
import android.os.Build
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.share.Route
import com.gatar.recipedealer.share.ShareRequest
import com.gatar.recipedealer.share.ShareRouter
import com.gatar.recipedealer.store.CaptureEntry
import com.gatar.recipedealer.store.CaptureState
import com.gatar.recipedealer.store.CaptureStatus
import com.gatar.recipedealer.store.CaptureStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.File

/** One share being processed: the request, its route and the capture's own files directory. */
class CaptureJob(val request: ShareRequest, val route: Route, val filesDir: File) {
    val id get() = request.captureId
    /** Shared files after the trampoline copied them into app storage (`uri` is then a local path). */
    val localFiles get() = route.files.map { File(it.uri) }
}

@Serializable
private data class SavedJob(val request: ShareRequest, val route: Route)

fun interface SourceExtractor {
    suspend fun extract(job: CaptureJob): Extraction
}

/** Routes a share, runs the matching on-device extractor and stores the resulting capture as a draft. */
class CaptureProcessor(
    private val context: Context,
    private val store: CaptureStore,
    private val extractors: Map<SourceType, SourceExtractor>,
) {
    fun route(request: ShareRequest): Route? = ShareRouter.route(request)

    fun start(request: ShareRequest, route: Route): CaptureJob {
        val label = request.subject ?: request.title ?: route.url ?: route.files.firstOrNull()?.displayName
            ?: request.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(80)
        store.create(request.captureId, request.capturedAt, CaptureState(CaptureStatus.EXTRACTING, route.type, label))
        return CaptureJob(request, route, store.filesDir(request.captureId))
    }

    /** Persists the job (with local file paths) so an interrupted capture can be processed again. */
    fun saveJob(job: CaptureJob) =
        File(store.dir(job.id), JOB).writeText(CaptureJson.encodeToString(SavedJob.serializer(), SavedJob(job.request, job.route)))

    fun loadJob(id: String): CaptureJob? = runCatching {
        val saved = CaptureJson.decodeFromString(SavedJob.serializer(), File(store.dir(id), JOB).readText())
        CaptureJob(saved.request, saved.route, store.filesDir(id))
    }.getOrNull()

    /** Captures left in EXTRACTING by a killed process (call before any job runs). */
    fun markInterrupted() {
        store.entries.value.filter { it.state.status == CaptureStatus.EXTRACTING }.forEach { e ->
            store.updateState(e.id) { it.copy(status = CaptureStatus.FAILED, error = "Przetwarzanie przerwane") }
        }
    }

    suspend fun run(job: CaptureJob): CaptureEntry {
        val t0 = System.currentTimeMillis()
        val extraction = try {
            extractors[job.route.type]?.extract(job) ?: Extraction(errors = listOf("no extractor for ${job.route.type}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Extraction(errors = listOf("extract: ${e.javaClass.simpleName}: ${e.message}"))
        }
        val withTotal = extraction.copy(timingsMs = extraction.timingsMs + ("total" to System.currentTimeMillis() - t0))
        val capture = CaptureBuilder.build(job.request, job.route, withTotal, clientInfo())
        // The copied originals are not needed any more unless an extractor kept them as attachments.
        File(job.filesDir, INCOMING).deleteRecursively()
        File(store.dir(job.id), JOB).delete()
        return store.saveCapture(capture, CaptureStatus.DRAFT)
    }

    fun clientInfo() = ClientInfo(
        appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?",
        androidSdk = Build.VERSION.SDK_INT,
        deviceModel = Build.MODEL,
    )

    companion object {
        /** Sub-directory of a capture's files dir where the trampoline copies shared files. */
        const val INCOMING = "incoming"
        private const val JOB = "job.json"
    }
}
