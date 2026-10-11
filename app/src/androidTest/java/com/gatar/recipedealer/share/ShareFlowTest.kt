package com.gatar.recipedealer.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gatar.recipedealer.container
import com.gatar.recipedealer.model.Attachment
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.store.CaptureStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Share → trampoline → service → stored capture, end to end on the device. */
@RunWith(AndroidJUnit4::class)
class ShareFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun textImage(name: String, lines: List<String>): Uri {
        val bmp = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; textSize = 56f; isAntiAlias = true }
        lines.forEachIndexed { i, l -> c.drawText(l, 60f, 150f + i * 90f, p) }
        val f = File(context.cacheDir, "exports/$name").also { it.parentFile!!.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    private fun awaitCapture(id: () -> String?): Capture = runBlocking {
        withTimeout(60_000) {
            while (true) {
                val e = id()?.let { context.container.store.get(it) }
                if (e?.capture != null && e.state.status == CaptureStatus.DRAFT) return@withTimeout e.capture!!
                delay(300)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    @Test fun sendMultipleImages() {
        context.container.settings.update { it.copy(backgroundMode = true, autoSend = false) }
        val before = context.container.store.entries.value.map { it.id }.toSet()
        val uris = arrayListOf(
            textImage("a.png", listOf("Składniki", "2 jajka", "1 szklanka mąki")),
            textImage("b.png", listOf("Przygotowanie", "Wymieszaj wszystko", "Smaż na patelni")),
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setClass(context, ShareActivity::class.java)
            .setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<ShareActivity>(intent).use {
            val c = awaitCapture { context.container.store.entries.value.map { it.id }.firstOrNull { it !in before } }
            assertEquals(SourceType.IMAGE, c.source.type)
            assertEquals(listOf("a.png", "b.png"), c.source.files)
            assertEquals(listOf("image-1.jpg", "image-2.jpg"), c.attachments.map { it.name })
            assertTrue(c.attachments.all { it.role == Attachment.ROLE_IMAGE && maxOf(it.width!!, it.height!!) <= 1600 })
            val ocr = c.raw.ocr!!
            assertEquals(setOf(0, 1), ocr.lines.map { it.page }.toSet())
            assertTrue(ocr.text, "jajka" in ocr.text && "patelni" in ocr.text)
            assertTrue('\u000C' in ocr.text)
            c.attachments.forEach { a -> assertTrue(File(context.container.store.filesDir(c.captureId), a.name).length() == a.bytes) }
            // the stored JSON round-trips
            CaptureJson.decodeFromString(Capture.serializer(), context.container.store.captureFile(c.captureId).readText())
        }
    }

    @Test fun textShare() {
        context.container.settings.update { it.copy(backgroundMode = true) }
        val before = context.container.store.entries.value.map { it.id }.toSet()
        val intent = Intent(Intent.ACTION_SEND).setClass(context, ShareActivity::class.java).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "​Naleśniki\r\n\r\nSkładniki\n2 jajka\n1 szklanka mleka\n\nPrzygotowanie\n1. Wymieszaj.\n2. Usmaż.")
        ActivityScenario.launch<ShareActivity>(intent).use {
            val c = awaitCapture { context.container.store.entries.value.map { it.id }.firstOrNull { it !in before } }
            assertEquals(SourceType.TEXT, c.source.type)
            assertEquals("Naleśniki", c.recipe.title)
            assertEquals(listOf("2 jajka", "1 szklanka mleka"), c.recipe.ingredientGroups.flatMap { it.items })
            assertEquals(listOf("Wymieszaj.", "Usmaż."), c.recipe.stepGroups.flatMap { it.items })
            assertEquals("pl", c.recipe.language)
            assertTrue(c.raw.text!!.startsWith("Naleśniki\n\nSkładniki"))
        }
    }
}
