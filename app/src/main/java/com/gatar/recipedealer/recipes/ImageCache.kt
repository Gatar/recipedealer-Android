package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.upload.HttpCaptureSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Recipe images on disk. Blobs (`thumbnailBlob`) are content-addressed and immutable: fetched once with the bearer
 * token, kept for good. `recipe.images[]` URLs point to other sites: fetched without the token.
 */
class ImageCache(
    private val dir: File,
    private val backend: () -> BackendClient?,
    private val client: OkHttpClient = HttpCaptureSender.defaultClient,
) {
    suspend fun blob(sha256: String): File? = withContext(Dispatchers.IO) {
        if (!BLOB_ID.matches(sha256)) return@withContext null
        val f = File(dir, sha256)
        if (f.exists()) return@withContext f
        (backend()?.blob(sha256) as? ApiResult.Ok)?.value?.let { write(f, it) }
    }

    suspend fun url(url: String): File? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        val f = File(dir, "url-" + sha256(url))
        if (f.exists()) return@withContext f
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()?.let { write(f, it) }
    }

    private fun write(f: File, bytes: ByteArray): File {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return f
    }

    private companion object {
        /** Used as a file name and a path segment. */
        val BLOB_ID = Regex("[0-9A-Za-z_-]{16,128}")
        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
