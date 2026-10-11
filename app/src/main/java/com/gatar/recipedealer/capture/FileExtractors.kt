package com.gatar.recipedealer.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import com.gatar.recipedealer.model.Attachment
import com.gatar.recipedealer.model.OcrLine
import com.gatar.recipedealer.model.OcrRaw
import com.gatar.recipedealer.model.OcrSource
import com.gatar.recipedealer.model.Raw
import com.gatar.recipedealer.ocr.ImageFiles
import com.gatar.recipedealer.ocr.OcrEngine
import com.gatar.recipedealer.ocr.Tiling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Device-side caps (attachments are capped on the device; the backend answers 413 otherwise). */
object Limits {
    const val MAX_IMAGES = 10
    const val MAX_IMAGE_TILES = 12
    const val ATTACHMENT_LONG_EDGE = 1600
    const val MAX_PDF_PAGES = 10
    const val PDF_RENDER_WIDTH = 1600
    const val MAX_PDF_BYTES = 25L * 1024 * 1024
}

/**
 * IMAGE route: per image EXIF orientation, ML Kit OCR (tiled for tall images), and a downscaled JPEG attachment
 * (long edge 1600 px) for the backend's vision step. No on-device structuring: the backend does it.
 */
class ImageSourceExtractor(private val ocr: OcrEngine) : SourceExtractor {
    override suspend fun extract(job: CaptureJob): Extraction = withContext(Dispatchers.Default) {
        val notes = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val timings = linkedMapOf<String, Long>()
        val attachments = mutableListOf<Attachment>()
        val lines = mutableListOf<OcrLine>()
        val texts = mutableListOf<String>()
        var truncated = false
        val files = job.localFiles
        if (files.size > Limits.MAX_IMAGES) notes += "only the first ${Limits.MAX_IMAGES} of ${files.size} images used"
        files.take(Limits.MAX_IMAGES).forEachIndexed { i, file ->
            val info = ImageFiles.info(file)
            if (info == null) {
                errors += "image ${i + 1}: cannot decode"
                return@forEachIndexed
            }
            // A long screenshot (Chrome sends the page URL along, or the page is very tall) keeps its width.
            val screenshot = job.route.url != null || info.height > info.width * 5 / 2
            val t0 = System.currentTimeMillis()
            runCatching {
                val name = "image-${i + 1}.jpg"
                val out = File(job.filesDir, name)
                val (w, h) = ImageFiles.writeJpeg(file, info, out, Limits.ATTACHMENT_LONG_EDGE, screenshot)
                attachments += Attachment(name, if (screenshot) Attachment.ROLE_SCREENSHOT else Attachment.ROLE_IMAGE,
                    "image/jpeg", w, h, out.length())
            }.onFailure { errors += "image ${i + 1} attachment: ${it.message}" }
            timings["image${i + 1}.jpeg"] = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            runCatching { ocr.recognize(ImageFiles.tileSource(file, info), Limits.MAX_IMAGE_TILES) }
                .onSuccess { r ->
                    lines += Tiling.toOcrLines(r.lines, i)
                    texts += r.text
                    truncated = truncated || r.truncated
                    notes += "image ${i + 1}: ${info.width}×${info.height}, rotation ${info.rotation}, ${r.lines.size} OCR lines" +
                        if (r.truncated) " (truncated)" else ""
                }
                .onFailure { errors += "image ${i + 1} ocr: ${it.message}" }
            timings["image${i + 1}.ocr"] = System.currentTimeMillis() - t1
        }
        Extraction(
            raw = Raw(ocr = OcrRaw(OcrSource.IMAGE, texts.joinToString("\u000C"), lines, truncated)),
            attachments = attachments,
            timingsMs = timings,
            errors = errors,
            notes = notes,
        )
    }
}

/**
 * PDF route: per page the text layer (exact characters; content-stream order for "Save as PDF" pages) and ML Kit
 * OCR of the page rendered at 1600 px width on white (visual order). Both are sent, plus the original PDF.
 */
class PdfSourceExtractor(private val ocr: OcrEngine) : SourceExtractor {
    override suspend fun extract(job: CaptureJob): Extraction = withContext(Dispatchers.Default) {
        val notes = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val timings = linkedMapOf<String, Long>()
        val attachments = mutableListOf<Attachment>()
        val pageTexts = mutableListOf<String>()
        val ocrTexts = mutableListOf<String>()
        val lines = mutableListOf<OcrLine>()
        var truncated = false
        var pageIndex = 0
        if (!textLayerAvailable()) notes += "PDF text layer not available on this Android version"
        job.localFiles.forEachIndexed { f, file ->
            val name = "document-${f + 1}.pdf"
            if (file.length() <= Limits.MAX_PDF_BYTES) {
                file.copyTo(File(job.filesDir, name), overwrite = true)
                attachments += Attachment(name, Attachment.ROLE_PDF, "application/pdf", bytes = file.length())
            } else {
                notes += "$name not attached: ${file.length() / 1024 / 1024} MB"
            }
            val t0 = System.currentTimeMillis()
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val count = renderer.pageCount
                        if (pageIndex + count > Limits.MAX_PDF_PAGES) truncated = true
                        for (p in 0 until count) {
                            if (pageIndex >= Limits.MAX_PDF_PAGES) break
                            renderer.openPage(p).use { page ->
                                pageTexts += if (textLayerAvailable()) runCatching { pageText(page) }.getOrElse {
                                    errors += "pdf ${f + 1} page ${p + 1} text: ${it.message}"; ""
                                } else ""
                                val bmp = render(page)
                                try {
                                    val found = ocr.recognize(bmp)
                                    val ordered = Tiling.readingOrder(found)
                                    lines += Tiling.toOcrLines(ordered, pageIndex)
                                    ocrTexts += ordered.joinToString("\n") { it.text }
                                } catch (e: Exception) {
                                    errors += "pdf ${f + 1} page ${p + 1} ocr: ${e.message}"
                                    ocrTexts += ""
                                } finally {
                                    bmp.recycle()
                                }
                            }
                            pageIndex++
                        }
                        notes += "$name: $count page(s)"
                    }
                }
            } catch (e: Exception) {
                errors += "$name: ${e.javaClass.simpleName}: ${e.message}"
            }
            timings["pdf${f + 1}"] = System.currentTimeMillis() - t0
        }
        if (truncated) notes += "only the first ${Limits.MAX_PDF_PAGES} pages read"
        Extraction(
            raw = Raw(
                pdfText = pageTexts.joinToString("\u000C").takeIf { it.isNotBlank() },
                ocr = OcrRaw(OcrSource.PDF_RENDER, ocrTexts.joinToString("\u000C"), lines, truncated),
            ),
            attachments = attachments,
            timingsMs = timings,
            errors = errors,
            notes = notes,
        )
    }

    private fun render(page: PdfRenderer.Page): Bitmap {
        val w = Limits.PDF_RENDER_WIDTH
        val h = maxOf(1, (page.height.toLong() * w / page.width.coerceAtLeast(1)).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    /** `getTextContents()`: Android 15+, or SDK extension S ≥ 13 on Android 14. */
    private fun textLayerAvailable() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    @SuppressLint("NewApi")
    private fun pageText(page: PdfRenderer.Page): String =
        page.textContents.joinToString("\n") { it.text }.replace("\r\n", "\n").replace('\r', '\n').trim()
}
