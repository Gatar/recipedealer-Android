package com.gatar.recipedealer.upload

import com.gatar.recipedealer.CaptureSchema
import com.gatar.recipedealer.model.Attachment
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.ShareInfo
import com.gatar.recipedealer.model.Source
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.store.CaptureStatus
import com.gatar.recipedealer.store.CaptureStore
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The uploader against a mock server for every response of the example API (final-app.md §8). */
class HttpCaptureSenderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val id = "3f6c1a52-8a0e-4c5e-9a43-2a1f0d6b7c11"

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()

    private fun base() = server.url("/api").toString()
    private fun sender(token: String? = "secret") = HttpCaptureSender(base(), token)

    private fun capture(attachments: List<Attachment> = emptyList()) = Capture(
        captureId = id, capturedAt = "2026-10-11T10:00:00Z", client = ClientInfo("1.0.0", 36, "Pixel 9"),
        share = ShareInfo(action = "android.intent.action.SEND", mimeType = "image/jpeg"),
        source = Source(SourceType.IMAGE, files = listOf("a.jpg")), recipe = Recipe(title = "Gofry"),
        attachments = attachments)

    private fun file(name: String, content: String) = tmp.newFile(name).apply { writeText(content) }

    private fun respond(code: Int, body: String = "{\"captureId\":\"$id\",\"status\":\"accepted\"}") =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun created201SendsMultipartWithCaptureAndAttachments() = runTest {
        respond(201)
        val a = Attachment("image-1.jpg", Attachment.ROLE_IMAGE, "image/jpeg", 1200, 1600, 5)
        val result = sender().send(capture(listOf(a)), mapOf("image-1.jpg" to file("image-1.jpg", "JPEG!")))
        assertEquals(SendResult.Accepted(created = true, destination = base().trimEnd('/')), result)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/captures/$id", req.path)
        assertEquals("Bearer secret", req.getHeader("Authorization"))
        val contentType = req.getHeader("Content-Type")!!
        assertTrue(contentType.startsWith("multipart/form-data"))
        val parts = mutableMapOf<String, Pair<String?, String>>()
        MultipartReader(Buffer().write(req.body.readByteArray()), contentType.substringAfter("boundary=")).use { r ->
            while (true) {
                val p = r.nextPart() ?: break
                val name = Regex("name=\"([^\"]+)\"").find(p.headers["Content-Disposition"]!!)!!.groupValues[1]
                parts[name] = p.headers["Content-Type"] to p.body.readUtf8()
            }
        }
        assertEquals(setOf("capture", "image-1.jpg"), parts.keys)
        assertTrue(parts["capture"]!!.first!!.startsWith("application/json"))
        assertEquals(emptyList<String>(), CaptureSchema.errors(parts["capture"]!!.second))
        assertEquals("image/jpeg" to "JPEG!", parts["image-1.jpg"])
    }

    @Test fun existing200IsAcceptedToo() = runTest {
        respond(200)
        assertEquals(SendResult.Accepted(created = false, destination = base().trimEnd('/')), sender().send(capture(), emptyMap()))
    }

    @Test fun badRequestCarriesDetails() = runTest {
        respond(400, """{"error":"invalid_capture","details":["recipe.title: expected string"]}""")
        assertEquals(SendResult.Invalid(listOf("recipe.title: expected string", "invalid_capture")), sender().send(capture(), emptyMap()))
    }

    @Test fun unauthorizedAndForbidden() = runTest {
        respond(401, ""); respond(403, "")
        assertEquals(SendResult.Unauthorized(401), sender().send(capture(), emptyMap()))
        assertEquals(SendResult.Unauthorized(403), sender().send(capture(), emptyMap()))
    }

    @Test fun payloadTooLarge() = runTest {
        respond(413, "")
        assertEquals(SendResult.TooLarge, sender().send(capture(), emptyMap()))
    }

    @Test fun retryOn429And5xxAndNetworkErrors() = runTest {
        respond(429, ""); respond(500, ""); respond(503, "")
        repeat(3) { val r = sender().send(capture(), emptyMap()); assertTrue("attempt $it: $r", r is SendResult.Retry) }
        // network error: nobody listening
        val url = base()
        server.shutdown()
        assertTrue(HttpCaptureSender(url, "secret").send(capture(), emptyMap()) is SendResult.Retry)
    }

    @Test fun noTokenNoAuthorizationHeader() = runTest {
        respond(201)
        sender(token = "").send(capture(), emptyMap())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun missingAttachmentFileIsNotSent() = runTest {
        val a = Attachment("image-1.jpg", Attachment.ROLE_IMAGE, "image/jpeg")
        assertTrue(sender().send(capture(listOf(a)), emptyMap()) is SendResult.Invalid)
        assertEquals(0, server.requestCount)
    }

    @Test fun statusEndpoint() = runTest {
        respond(200, """{"captureId":"$id","status":"done","recipes":[{"recipeId":"r1","title":"Gofry holenderskie"},{"recipeId":"r2","title":"Wafle"}],"message":null}""")
        respond(404, "")
        assertEquals(BackendStatus("done", listOf("Gofry holenderskie", "Wafle"), null), sender().status(id))
        assertEquals("GET", server.takeRequest().method)
        assertNull(sender().status(id))
    }

    @Test fun health() = runTest {
        respond(200, """{"status":"ok"}"""); respond(401, ""); respond(500, "")
        assertEquals(HttpCaptureSender.Health.Ok, sender().health())
        assertEquals("/api/v1/health", server.takeRequest().path)
        assertEquals(HttpCaptureSender.Health.Unauthorized, sender().health())
        assertTrue(sender().health() is HttpCaptureSender.Health.Failed)
        server.shutdown()
        assertTrue(sender().health() is HttpCaptureSender.Health.Failed)
    }

    @Test fun runnerStateTransitions() = runTest {
        val store = CaptureStore(tmp.newFolder("captures"))
        store.saveCapture(capture(), CaptureStatus.QUEUED)
        val runner = CaptureUploadRunner(store)
        respond(503, ""); respond(401, ""); respond(201)

        runner.upload(id, sender())
        store.get(id)!!.state.let { assertEquals(CaptureStatus.QUEUED, it.status); assertTrue(it.error!!.contains("503")) }
        runner.upload(id, sender())
        store.get(id)!!.state.let { assertEquals(CaptureStatus.FAILED, it.status); assertTrue(it.needsAuth) }
        runner.upload(id, sender())
        store.get(id)!!.state.let {
            assertEquals(CaptureStatus.SENT, it.status)
            assertEquals(base().trimEnd('/'), it.destination)
            assertEquals(false, it.needsAuth)
            assertNull(it.error)
            assertEquals(3, it.attempts)
        }
        // every attempt used the same capture id (idempotent PUT)
        repeat(3) { assertEquals("/api/v1/captures/$id", server.takeRequest().path) }
    }

    @Suppress("unused") private fun keep(f: File) = f
}

class UrlRulesTest {
    @Test fun httpsOnlyExceptEmulatorHost() {
        assertTrue(com.gatar.recipedealer.ui.isAllowedUrl("https://recipes.example.org"))
        assertTrue(com.gatar.recipedealer.ui.isAllowedUrl("http://10.0.2.2:8080"))
        assertEquals(false, com.gatar.recipedealer.ui.isAllowedUrl("http://recipes.example.org"))
        assertEquals(false, com.gatar.recipedealer.ui.isAllowedUrl("recipes.example.org"))
    }
}
