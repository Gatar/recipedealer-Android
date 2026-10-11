package com.gatar.recipedealer.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Something tall that can be rendered band by band (an image file, a WebView). */
interface TileSource {
    val width: Int
    val height: Int
    /** Renders `[top, bottom)` at full width. The caller recycles the bitmap. */
    suspend fun render(top: Int, bottom: Int): Bitmap
}

data class OcrResult(val lines: List<TextLine>, val truncated: Boolean) {
    val text get() = lines.joinToString("\n") { it.text }
}

/** ML Kit text recognition (Latin, bundled model). Uses lines, not blocks: ML Kit can merge a whole tile into one block. */
class OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): List<TextLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                cont.resume(text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val b = line.boundingBox ?: return@mapNotNull null
                    line.text.trim().takeIf { it.isNotEmpty() }?.let { TextLine(it, b.left, b.top, b.right, b.bottom) }
                })
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Tiled recognition of a tall source, lines in reading order. */
    suspend fun recognize(source: TileSource, maxTiles: Int): OcrResult {
        val (tiles, truncated) = Tiling.tiles(source.height, maxTiles)
        val lines = tiles.flatMap { tile ->
            val bmp = source.render(tile.top, tile.bottom)
            try { Tiling.own(tile, recognize(bmp)) } finally { bmp.recycle() }
        }
        return OcrResult(Tiling.readingOrder(lines), truncated)
    }
}
