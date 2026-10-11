package com.gatar.recipedealer.store

import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/** Local life cycle of a capture; not part of the contract. */
@Serializable
enum class CaptureStatus {
    /** The share is being processed on the device. */
    EXTRACTING,
    /** Extracted, waiting for the user to send it (or for auto-send). */
    DRAFT,
    /** Waiting for upload (offline queue). */
    QUEUED,
    SENT,
    /** Upload failed for good (400 / 413) or needs the user (401 / 403); can be retried by hand. */
    FAILED,
}

@Serializable
data class CaptureState(
    val status: CaptureStatus,
    val type: SourceType? = null,
    /** What to show before the recipe has a title: shared URL, subject or file name. */
    val label: String? = null,
    val updatedAt: String = Instant.now().toString(),
    val error: String? = null,
    val attempts: Int = 0,
    /** Failed with 401 / 403: re-queued once the token is changed in the settings. */
    val needsAuth: Boolean = false,
    /** Where it was sent: "local" or the backend base URL. */
    val destination: String? = null,
    /** From `GET /v1/captures/{id}` (optional). */
    val backendStatus: String? = null,
    val backendMessage: String? = null,
    val backendTitles: List<String> = emptyList(),
)

data class CaptureEntry(val id: String, val capturedAt: String, val state: CaptureState, val capture: Capture?) {
    val title: String? get() = capture?.recipe?.title?.takeIf { it.isNotBlank() } ?: state.label
    val type: SourceType? get() = capture?.source?.type ?: state.type
}

/**
 * File-based store: `<root>/<captureId>/` holds `capture.json`, `state.json`, `capturedAt` and the attachment files
 * under `files/`. One household, a few dozen captures – no database needed.
 */
class CaptureStore(private val root: File) {
    private val _entries = MutableStateFlow<List<CaptureEntry>>(emptyList())
    val entries: StateFlow<List<CaptureEntry>> = _entries.asStateFlow()

    init {
        root.mkdirs()
        _entries.value = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::read).sortedByCaptured()
    }

    fun dir(id: String) = File(root, id)
    fun filesDir(id: String) = File(dir(id), FILES).also { it.mkdirs() }
    fun captureFile(id: String) = File(dir(id), CAPTURE)
    fun get(id: String): CaptureEntry? = _entries.value.firstOrNull { it.id == id }

    @Synchronized
    fun create(id: String, capturedAt: String, state: CaptureState): CaptureEntry {
        val d = dir(id).also { it.mkdirs() }
        File(d, CAPTURED_AT).writeText(capturedAt)
        writeAtomic(File(d, STATE), CaptureJson.encodeToString(CaptureState.serializer(), state))
        return CaptureEntry(id, capturedAt, state, null).also(::publish)
    }

    @Synchronized
    fun saveCapture(capture: Capture, status: CaptureStatus? = null): CaptureEntry {
        val id = capture.captureId
        val d = dir(id).also { it.mkdirs() }
        File(d, CAPTURED_AT).writeText(capture.capturedAt)
        writeAtomic(File(d, CAPTURE), CaptureJson.encodeToString(Capture.serializer(), capture))
        val old = get(id)?.state ?: CaptureState(CaptureStatus.DRAFT, capture.source.type)
        val state = old.copy(status = status ?: old.status, type = capture.source.type, updatedAt = now())
        writeAtomic(File(d, STATE), CaptureJson.encodeToString(CaptureState.serializer(), state))
        return CaptureEntry(id, capture.capturedAt, state, capture).also(::publish)
    }

    @Synchronized
    fun updateState(id: String, change: (CaptureState) -> CaptureState): CaptureEntry? {
        val entry = get(id) ?: return null
        val state = change(entry.state).copy(updatedAt = now())
        writeAtomic(File(dir(id), STATE), CaptureJson.encodeToString(CaptureState.serializer(), state))
        return entry.copy(state = state).also(::publish)
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        _entries.update { list -> list.filterNot { it.id == id } }
    }

    private fun publish(entry: CaptureEntry) =
        _entries.update { list -> (list.filterNot { it.id == entry.id } + entry).sortedByCaptured() }

    private fun read(d: File): CaptureEntry? = runCatching {
        val state = CaptureJson.decodeFromString(CaptureState.serializer(), File(d, STATE).readText())
        val capture = File(d, CAPTURE).takeIf { it.exists() }
            ?.let { CaptureJson.decodeFromString(Capture.serializer(), it.readText()) }
        val capturedAt = capture?.capturedAt ?: File(d, CAPTURED_AT).takeIf { it.exists() }?.readText() ?: ""
        CaptureEntry(d.name, capturedAt, state, capture)
    }.getOrNull()

    private fun List<CaptureEntry>.sortedByCaptured() = sortedByDescending { it.capturedAt }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun now() = Instant.now().toString()

    companion object {
        const val CAPTURE = "capture.json"
        const val STATE = "state.json"
        const val CAPTURED_AT = "capturedAt"
        const val FILES = "files"
    }
}
