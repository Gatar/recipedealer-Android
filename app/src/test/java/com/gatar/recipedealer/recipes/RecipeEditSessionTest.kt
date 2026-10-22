package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiJson
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.SyncItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RecipeEditSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var cache: RecipeCache
    private val rid = "7d1c2f0e-0b7a-4a51-9a6e-1c2d3e4f5a6b"
    private val lockId = "0c9f1e7a-3b2d-4c5e-8f9a-112233445566"
    private val lockJson = """{"lockId":"$lockId","expiresAt":"2026-10-22T19:30:00Z"}"""

    @Before fun start() {
        // an unscripted call (e.g. the lock release on cancel) gets a 404 instead of hanging
        server = MockWebServer().apply { (dispatcher as QueueDispatcher).setFailFast(true); start() }
        cache = RecipeCache(File(tmp.newFolder("recipes"), "recipes.json"))
        cache.apply(listOf(ApiJson.decodeFromString(SyncItem.serializer(),
            """{"recipeId":"$rid","updatedAt":"2026-10-22T10:00:00Z","headRevisionId":"h1","recipe":{"title":"Gofry"}}""")), "c1")
    }

    @After fun stop() {
        beat.cancel()
        server.shutdown()
    }

    /**
     * The heartbeat runs on its own virtual clock: runTest's clock jumps ahead whenever the test waits for real I/O,
     * which would fire heartbeats on its own.
     */
    private val beat = TestScope()

    private fun beatAfter(millis: Long) {
        beat.testScheduler.advanceTimeBy(millis)
        beat.testScheduler.runCurrent()
    }

    /** The heartbeat's HTTP call answered: let it go back to its delay. */
    private fun settle() = repeat(30) { beat.testScheduler.runCurrent(); Thread.sleep(10) }

    /** Lets the heartbeat coroutine resume after its (real) HTTP call. */
    private fun pumpUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            beat.testScheduler.runCurrent()
            Thread.sleep(10)
        }
    }

    private fun session(scope: CoroutineScope = beat) = RecipeEditSession(BackendClient(server.url("/").toString(), "secret"), cache, rid, scope)

    private fun respond(code: Int, body: String = "", etag: String? = null) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).apply { etag?.let { addHeader("ETag", it) } })

    private fun view(head: String, title: String) =
        """{"recipeId":"$rid","headRevisionId":"$head","origin":"USER","recipe":{"title":"$title","author":"Ania"},"urls":["https://x/gofry"]}"""

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    /** GET head (ETag rewritten by a compressing proxy, as Caddy does) + POST lock. */
    private suspend fun started(): RecipeEditSession {
        respond(200, view("h1", "Gofry"), "\"h1-gzip\"")
        respond(201, lockJson)
        val s = session()
        assertTrue(s.start() is ApiResult.Ok)
        assertEquals("GET /v1/recipes/$rid", take().requestLine.substringBeforeLast(' '))
        assertEquals("POST /v1/recipes/$rid/lock", take().requestLine.substringBeforeLast(' '))
        return s
    }

    @Test fun startTakesTheLockAndKeepsTheHeadEtag() = runTest {
        val s = started()
        s.state.value.let {
            assertEquals(RecipeEditSession.Phase.EDITING, it.phase)
            assertEquals(lockId, it.lockId)
            assertEquals("\"h1\"", it.etag)   // from headRevisionId, not the rewritten header
            assertEquals("Gofry", it.initial!!.title)
        }
        assertEquals(listOf("https://x/gofry"), cache.get(rid)!!.urls)
        s.cancel()
    }

    @Test fun lockedByAnotherPhone() = runTest {
        respond(200, view("h1", "Gofry"), "\"h1\"")
        respond(409, """{"error":"locked","lockedBy":"Ania (Pixel 9)","expiresAt":"2026-10-22T19:30:00Z"}""")
        val s = session()
        assertEquals(ApiResult.Locked("Ania (Pixel 9)", "2026-10-22T19:30:00Z"), s.start())
        assertEquals(RecipeEditSession.Phase.LOCKED_BY_OTHER, s.state.value.phase)
        assertEquals("Ania (Pixel 9)", s.state.value.lockedBy)
        assertNull(s.state.value.lockId)
    }

    @Test fun heartbeatEveryTwoMinutesUntilTheLockIsLost() = runTest {
        val s = started()
        respond(200, lockJson); respond(200, lockJson); respond(409, """{"error":"lock_expired"}""")
        beatAfter(RecipeEditSession.HEARTBEAT_MILLIS - 1_000)
        assertNull("no heartbeat before 2 min", server.takeRequest(300, TimeUnit.MILLISECONDS))
        beatAfter(1_000)
        repeat(3) { i ->
            take().let { r ->
                assertEquals("PUT", r.method)
                assertEquals("/v1/recipes/$rid/lock/$lockId", r.path)
                assertEquals("Bearer secret", r.getHeader("Authorization"))
            }
            settle()
            if (i < 2) beatAfter(RecipeEditSession.HEARTBEAT_MILLIS)
        }
        pumpUntil { s.state.value.lockLost }
        assertEquals(ApiErrors.LOCK_EXPIRED, (s.state.value.error as ApiResult.Failed).error)
        assertEquals(RecipeEditSession.Phase.EDITING, s.state.value.phase)   // the editor stays open
        beatAfter(10 * RecipeEditSession.HEARTBEAT_MILLIS)
        assertNull("no heartbeat after the lock was lost", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun networkErrorDuringHeartbeatKeepsBeating() = runTest {
        val s = started()
        respond(503); respond(200, lockJson)
        beatAfter(RecipeEditSession.HEARTBEAT_MILLIS)
        take()
        settle()
        beatAfter(RecipeEditSession.HEARTBEAT_MILLIS)
        assertEquals("PUT", take().method)
        assertFalse(s.state.value.lockLost)
        s.cancel()
    }

    @Test fun saveSendsLockIdIfMatchAndMergedBody() = runTest {
        val s = started()
        respond(201, view("h2", "Gofry belgijskie"), "\"h2\"")
        val r = s.save(s.state.value.initial!!.copy(title = "Gofry belgijskie"))
        assertEquals(201, (r as ApiResult.Ok).code)
        take().let { req ->
            assertEquals("POST", req.method)
            assertEquals("/v1/recipes/$rid/revisions", req.path)
            assertEquals(lockId, req.getHeader("Lock-Id"))
            assertEquals("\"h1\"", req.getHeader("If-Match"))
            val body = ApiJson.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("Gofry belgijskie"), body["title"])
            assertEquals(JsonPrimitive("Ania"), body["author"])
        }
        assertEquals(RecipeEditSession.Phase.FINISHED, s.state.value.phase)
        assertEquals("Gofry belgijskie", cache.get(rid)!!.title)
        assertEquals("h2", cache.get(rid)!!.headRevisionId)
        // the server released the lock: no heartbeat, no DELETE on close
        s.cancel()
        beatAfter(3 * RecipeEditSession.HEARTBEAT_MILLIS)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun headChangedKeepsTheSessionForTheUsersText() = runTest {
        val s = started()
        respond(412, """{"error":"head_changed"}""")
        val edited = s.state.value.initial!!.copy(title = "Moje gofry", notes = listOf("Mój tekst"))
        assertEquals(412, (s.save(edited) as ApiResult.Failed).code)
        take()
        s.state.value.let {
            assertTrue(it.headChanged)
            assertEquals(RecipeEditSession.Phase.EDITING, it.phase)
            assertEquals(lockId, it.lockId)
        }
        assertEquals("Gofry", cache.get(rid)!!.title)   // nothing overwritten locally
        // reload: release the old lock, start again on the new head
        respond(204)
        respond(200, view("h3", "Gofry (Ania)"), "\"h3\""); respond(201, lockJson)
        s.cancel()
        assertEquals("DELETE /v1/recipes/$rid/lock/$lockId", take().requestLine.substringBeforeLast(' '))
        s.start()
        take(); take()
        assertEquals("\"h3\"", s.state.value.etag)
        assertFalse(s.state.value.headChanged)
        s.cancel()
    }

    @Test fun invalidRecipeKeepsEditing() = runTest {
        val s = started()
        respond(400, """{"error":"invalid_recipe","details":["$.title: expected string"]}""")
        s.save(s.state.value.initial!!)
        assertEquals(RecipeEditSession.Phase.EDITING, s.state.value.phase)
        assertEquals(listOf("$.title: expected string"), (s.state.value.error as ApiResult.Failed).details)
        s.cancel()
    }

    @Test fun cancelReleasesTheLockAndStopsTheHeartbeat() = runTest {
        val s = started()
        respond(204)
        s.cancel()
        take().let {
            assertEquals("DELETE", it.method)
            assertEquals("/v1/recipes/$rid/lock/$lockId", it.path)
        }
        s.cancel()   // idempotent: nothing more to release
        beatAfter(3 * RecipeEditSession.HEARTBEAT_MILLIS)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun deleteIsASoftDeleteInsideTheSession() = runTest {
        val s = started()
        respond(204)
        assertEquals(ApiResult.Ok(Unit, 204), s.delete())
        take().let {
            assertEquals("DELETE", it.method)
            assertEquals("/v1/recipes/$rid", it.path)
            assertEquals(lockId, it.getHeader("Lock-Id"))
            assertEquals("\"h1\"", it.getHeader("If-Match"))
        }
        assertTrue(s.state.value.deleted)
        assertNull(cache.get(rid))
    }

    @Test fun goneRecipeLeavesTheCache() = runTest {
        respond(410, """{"error":"recipe_deleted"}""")
        val s = session()
        s.start()
        assertEquals(RecipeEditSession.Phase.FAILED, s.state.value.phase)
        assertNull(cache.get(rid))
    }

    @Test fun startFromARevisionKeepsItsContent() = runTest {
        respond(200, view("h1", "Gofry"), "\"h1\"")
        respond(201, lockJson)
        val s = session()
        s.start(ApiJson.parseToJsonElement("""{"title":"Gofry (propozycja)","extra":1}""").jsonObject)
        assertEquals("Gofry (propozycja)", s.state.value.initial!!.title)
        respond(201, view("h2", "Gofry (propozycja)"), "\"h2\"")
        s.save(s.state.value.initial!!)
        take(); take()
        assertEquals(JsonPrimitive(1), ApiJson.parseToJsonElement(take().body.readUtf8()).jsonObject["extra"])
    }
}
