package com.gatar.recipedealer.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File

/** Decoding of shared images: EXIF orientation, tiles for OCR, downscaled JPEG attachments. */
object ImageFiles {
    /** [width] × [height] after applying [rotation] (degrees clockwise from EXIF). */
    data class Info(val width: Int, val height: Int, val rotation: Int, val rawWidth: Int, val rawHeight: Int)

    /** Bitmaps wider than this are subsampled for OCR (memory: a full-width 4096 px tile is decoded at once). */
    private const val OCR_MAX_WIDTH = 3000
    private const val ROTATED_MAX_EDGE = Tiling.TILE

    fun info(file: File): Info? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val swap = rotation == 90 || rotation == 270
        return Info(if (swap) o.outHeight else o.outWidth, if (swap) o.outWidth else o.outHeight, rotation, o.outWidth, o.outHeight)
    }

    private fun sampleFor(size: Int, max: Int): Int {
        var s = 1
        while (size / (s * 2) >= max) s *= 2
        return if (size / s > max) s * 2 else s
    }

    /** Largest power of 2 that keeps [size] at least [target]. */
    private fun downSample(size: Int, target: Int): Int {
        var s = 1
        while (size / (s * 2) >= target) s *= 2
        return s
    }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (r !== b) b.recycle()
        return r
    }

    /**
     * OCR source in the (rotated) image's pixel coordinates. Unrotated images are decoded band by band
     * (`BitmapRegionDecoder`); rotated ones (camera photos, never very tall) are decoded whole and rotated.
     */
    fun tileSource(file: File, info: Info): TileSource {
        if (info.rotation != 0) {
            val sample = sampleFor(maxOf(info.rawWidth, info.rawHeight), ROTATED_MAX_EDGE)
            val bmp = rotate(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })!!, info.rotation)
            return object : TileSource {
                override val width = info.width
                override val height = info.height
                override val sample = sample
                override suspend fun render(top: Int, bottom: Int): Bitmap {
                    val t = (top / sample).coerceIn(0, bmp.height - 1)
                    val h = ((bottom - top) / sample).coerceIn(1, bmp.height - t)
                    return Bitmap.createBitmap(bmp, 0, t, bmp.width, h).let { if (it === bmp) bmp.copy(bmp.config!!, false) else it }
                }
                override fun close() = bmp.recycle()
            }
        }
        val decoder = BitmapRegionDecoder.newInstance(file.path)
        val sample = sampleFor(info.width, OCR_MAX_WIDTH)
        return object : TileSource {
            override val width = info.width
            override val height = info.height
            override val sample = sample
            override suspend fun render(top: Int, bottom: Int): Bitmap =
                decoder.decodeRegion(Rect(0, top, info.width, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
            override fun close() = decoder.recycle()
        }
    }

    /**
     * JPEG (quality 85) with orientation applied, scaled so the long edge ≤ [maxLongEdge] – or, for a long
     * screenshot, so the width ≤ [maxLongEdge] and the height ≤ [maxHeight] (downscaling a 1080×11513 page to a
     * 1600 px long edge would make it unreadable). Returns the written size.
     */
    fun writeJpeg(file: File, info: Info, out: File, maxLongEdge: Int, screenshot: Boolean, maxHeight: Int = 20_000): Pair<Int, Int> {
        val scale = if (screenshot) minOf(1.0, maxLongEdge.toDouble() / info.width)
            else minOf(1.0, maxLongEdge.toDouble() / maxOf(info.width, info.height))
        val visibleHeight = if (screenshot) minOf(info.height, (maxHeight / scale).toInt()) else info.height
        val targetW = maxOf(1, Math.round(info.width * scale).toInt())
        val targetH = maxOf(1, Math.round(visibleHeight * scale).toInt())
        // decode at ≥ the target size, then scale exactly
        val sample = if (screenshot) downSample(info.width, targetW) else downSample(maxOf(info.width, info.height), maxOf(targetW, targetH))
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = if (info.rotation == 0 && visibleHeight < info.height) {
            BitmapRegionDecoder.newInstance(file.path).let { d ->
                try { d.decodeRegion(Rect(0, 0, info.width, visibleHeight), opts) } finally { d.recycle() }
            }
        } else {
            BitmapFactory.decodeFile(file.path, opts)
        } ?: error("cannot decode ${file.name}")
        val rotated = rotate(decoded, info.rotation)
        val scaled = if (rotated.width == targetW && rotated.height == targetH) rotated
            else Bitmap.createScaledBitmap(rotated, targetW, targetH, true).also { if (it !== rotated) rotated.recycle() }
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return (scaled.width to scaled.height).also { scaled.recycle() }
    }
}
