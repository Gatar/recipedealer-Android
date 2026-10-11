package com.gatar.recipedealer.store

import com.gatar.recipedealer.model.Attachment
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.ShareInfo
import com.gatar.recipedealer.model.Source
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.upload.CaptureUploadRunner
import com.gatar.recipedealer.upload.LocalCaptureSender
import com.gatar.recipedealer.upload.SendResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun capture(id: String, at: String, attachments: List<Attachment> = emptyList()) = Capture(
        captureId = id, capturedAt = at, client = ClientInfo("1", 34, "x"), share = ShareInfo(),
        source = Source(SourceType.TEXT), recipe = Recipe(title = "T$id"), attachments = attachments)

    @Test fun lifecycleSurvivesReload() {
        val root = tmp.newFolder("captures")
        val store = CaptureStore(root)
        store.create("a", "2026-10-01T10:00:00Z", CaptureState(CaptureStatus.EXTRACTING, SourceType.WEB, "https://x"))
        assertEquals("https://x", store.get("a")!!.title)
        store.saveCapture(capture("a", "2026-10-01T10:00:00Z"), CaptureStatus.DRAFT)
        store.saveCapture(capture("b", "2026-10-02T10:00:00Z"), CaptureStatus.DRAFT)
        store.updateState("a") { it.copy(status = CaptureStatus.SENT, destination = "local") }

        val reloaded = CaptureStore(root)
        assertEquals(listOf("b", "a"), reloaded.entries.value.map { it.id })
        assertEquals(CaptureStatus.SENT, reloaded.get("a")!!.state.status)
        assertEquals("Ta", reloaded.get("a")!!.title)
        reloaded.delete("a")
        assertNull(CaptureStore(root).get("a"))
    }

    @Test fun localSenderMarksSent() = runTest {
        val store = CaptureStore(tmp.newFolder("captures"))
        store.saveCapture(capture("a", "2026-10-01T10:00:00Z", listOf(Attachment("image-1.jpg", "image", "image/jpeg"))), CaptureStatus.DRAFT)
        val runner = CaptureUploadRunner(store)
        // attachment file missing → invalid
        assertTrue(runner.upload("a", LocalCaptureSender()) is SendResult.Invalid)
        assertEquals(CaptureStatus.FAILED, store.get("a")!!.state.status)
        File(store.filesDir("a"), "image-1.jpg").writeText("x")
        assertTrue(runner.upload("a", LocalCaptureSender()) is SendResult.Accepted)
        val s = store.get("a")!!.state
        assertEquals(CaptureStatus.SENT, s.status)
        assertEquals("local", s.destination)
        assertEquals(2, s.attempts)
        assertNull(s.error)
    }
}
