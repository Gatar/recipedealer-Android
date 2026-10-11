package com.gatar.recipedealer.share

import com.gatar.recipedealer.model.ShareInfo
import com.gatar.recipedealer.model.SourceType
import kotlinx.serialization.Serializable

/** A shared file as received: a content URI (readable by us until copied) plus what the sender told about it. */
@Serializable
data class SharedFile(val uri: String, val mimeType: String?, val displayName: String?)

/**
 * Everything a share delivered, in plain values (no Android types), so routing is unit-testable and the request
 * can be handed to the capture service.
 */
@Serializable
data class ShareRequest(
    val captureId: String,
    val capturedAt: String,
    val action: String?,
    val mimeType: String?,
    val text: String?,
    val subject: String?,
    val title: String?,
    val referrer: String?,
    val files: List<SharedFile> = emptyList(),
    /** Summary of the intent extras (keys and value types), for diagnostics. */
    val extras: List<String> = emptyList(),
) {
    val shareInfo get() = ShareInfo(action, mimeType, referrer, subject, title)
}

@Serializable
data class Route(val type: SourceType, val url: String?, val files: List<SharedFile>, val notes: List<String>)

object ShareRouter {
    private val SINGLE_URL = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)
    private val EMBEDDED_URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun isPdf(f: SharedFile) = f.mimeType == "application/pdf" || f.displayName?.endsWith(".pdf", true) == true
    fun isImage(f: SharedFile) = f.mimeType?.startsWith("image/") == true

    /**
     * Text that is, after trim, a single http(s) URL → WEB; other text → TEXT; images → IMAGE; PDF → PDF.
     * Files win over text: a Chrome long screenshot is an image with the page URL in EXTRA_TEXT, kept as `url`.
     */
    fun route(r: ShareRequest): Route? {
        val text = r.text?.trim().orEmpty()
        val singleUrl = text.takeIf { SINGLE_URL.matches(it) }
        val pdfs = r.files.filter(::isPdf)
        val images = r.files.filter(::isImage)
        val notes = mutableListOf<String>()
        val skipped = r.files.size - pdfs.size - images.size
        if (skipped > 0) notes += "ignored $skipped shared file(s) of unsupported type"
        return when {
            images.isNotEmpty() && (pdfs.isEmpty() || isImage(r.files.first())) -> {
                if (pdfs.isNotEmpty()) notes += "ignored ${pdfs.size} PDF(s) shared together with images"
                Route(SourceType.IMAGE, singleUrl ?: firstUrl(text), images, notes)
            }
            pdfs.isNotEmpty() -> {
                if (images.isNotEmpty()) notes += "ignored ${images.size} image(s) shared together with a PDF"
                Route(SourceType.PDF, singleUrl ?: firstUrl(text), pdfs, notes)
            }
            singleUrl != null -> Route(SourceType.WEB, singleUrl, emptyList(), notes)
            text.isNotEmpty() -> Route(SourceType.TEXT, null, emptyList(), notes)
            else -> null
        }
    }

    fun firstUrl(text: String): String? = EMBEDDED_URL.find(text)?.value
}
