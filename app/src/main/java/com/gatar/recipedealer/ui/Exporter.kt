package com.gatar.recipedealer.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gatar.recipedealer.store.CaptureStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** "Share JSON" and ZIP export of a capture – the local sender's way out of the phone. */
object Exporter {
    private fun authority(context: Context) = context.packageName + ".files"

    fun shareJson(context: Context, store: CaptureStore, id: String) {
        val uri = FileProvider.getUriForFile(context, authority(context), store.captureFile(id))
        share(context, uri, "application/json")
    }

    fun shareZip(context: Context, store: CaptureStore, id: String) {
        val out = File(context.cacheDir, "exports/capture-$id.zip").also { it.parentFile?.mkdirs() }
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(CaptureStore.CAPTURE))
            store.captureFile(id).inputStream().use { it.copyTo(zip) }
            store.filesDir(id).listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
            }
        }
        share(context, FileProvider.getUriForFile(context, authority(context), out), "application/zip")
    }

    private fun share(context: Context, uri: android.net.Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private val DISPLAY = DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

fun formatInstant(s: String): String = runCatching { DISPLAY.format(Instant.parse(s)) }.getOrDefault(s)
