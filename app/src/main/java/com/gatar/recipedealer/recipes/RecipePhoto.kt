package com.gatar.recipedealer.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A recipe photo picked on the phone, scaled down before the upload (a camera photo has several MB): upright
 * (ImageDecoder applies the EXIF orientation), longer edge at most [MAX_EDGE] px, JPEG. The server re-encodes it to the
 * same size limit anyway.
 */
object RecipePhoto {
    const val MAX_EDGE = 1600
    private const val QUALITY = 85
    private val REF = Regex("^blob:([0-9a-f]{64})$")

    /** `blob:<sha256>` in `images[0]`: a photo uploaded from a phone. */
    fun blobOf(image: String?): String? = image?.trim()?.let { REF.find(it)?.groupValues?.get(1) }

    /** @throws java.io.IOException if the image cannot be decoded */
    fun prepare(context: Context, uri: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_EDGE.toDouble() / max(w, h)
            if (scale < 1) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
            .also { bitmap.recycle() }
    }
}
