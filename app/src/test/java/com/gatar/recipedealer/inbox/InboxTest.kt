package com.gatar.recipedealer.inbox

import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.CaptureStates
import com.gatar.recipedealer.api.RecipeRef
import com.gatar.recipedealer.api.Resolution
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.ShareInfo
import com.gatar.recipedealer.model.Source
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.store.CaptureStatus
import com.gatar.recipedealer.store.CaptureStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InboxTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var store: CaptureStore
    private lateinit var inbox: Inbox
    private val cid = "5408aea6-4419-484e-9194-fc7455bd4b00"
    private val rid = "7d1c2f0e-0b7a-4a51-9a6e-1c2d3e4f5a6b"

    @Before fun start() {
        server = MockWebServer().apply { start() }
        store = CaptureStore(tmp.newFolder("captures"))
        store.saveCapture(Capture(captureId = cid, capturedAt = "2026-10-22T19:20:00Z", client = ClientInfo("1", 36, "Pixel 9"),
            share = ShareInfo(), source = Source(SourceType.WEB, url = "https://x/gofry"), recipe = Recipe(title = "Gofry")),
            CaptureStatus.SENT)
        inbox = Inbox(store) { BackendClient(server.url("/").toString(), "secret") }
    }

    @After fun stop() = server.shutdown()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private val needsDecision = """{"captureId":"$cid","status":"needs_decision","receivedAt":"2026-10-22T19:21:00Z",
        "candidates":[{"index":0,"title":"Gofry","ingredientCount":5,"stepCount":4,"urls":["https://x/gofry"]}]}"""
    private val done = """{"captureId":"$cid","status":"done","recipes":[{"recipeId":"$rid","title":"Gofry"}],"message":"Zapisano"}"""

    @Test fun refreshListsOtherPhonesAndUpdatesLocalState() = runTest {
        respond(200, """[$needsDecision,{"captureId":"other-phone","status":"needs_attention","reason":"PROCESSING_FAILED"}]""")
        inbox.refresh()
        assertEquals(listOf(cid, "other-phone"), inbox.items.value.map { it.captureId })
        val s = store.get(cid)!!.state
        assertEquals(CaptureStates.NEEDS_DECISION, s.backendStatus)
        assertEquals("https://x/gofry", s.candidates.single().urls.single())
        assertEquals(null, store.get("other-phone"))
    }

    @Test fun awaitingExtractionStaysOutOfTheInboxButUpdatesLocalState() = runTest {
        val awaiting = """{"captureId":"$cid","status":"needs_attention","reason":"AWAITING_EXTRACTION"}"""
        respond(200, """[$awaiting,{"captureId":"other-phone","status":"needs_attention","reason":"AWAITING_EXTRACTION"}]""")
        respond(200, awaiting)
        inbox.refresh()
        assertEquals(emptyList<String>(), inbox.items.value.map { it.captureId })
        assertEquals(Inbox.AWAITING_EXTRACTION, store.get(cid)!!.state.backendReason)
        inbox.load(cid)
        assertEquals(emptyList<String>(), inbox.items.value.map { it.captureId })
    }

    @Test fun resolveNewMakesCaptureDoneAndLeavesTheInbox() = runTest {
        respond(200, "[$needsDecision]")
        respond(200, done)
        inbox.refresh()
        val r = inbox.resolve(cid, 0, Resolution.new())
        assertEquals(CaptureStates.DONE, (r as ApiResult.Ok).value.status)
        assertTrue(inbox.items.value.isEmpty())
        store.get(cid)!!.state.let {
            assertEquals(CaptureStates.DONE, it.backendStatus)
            assertEquals(listOf(RecipeRef(rid, "Gofry")), it.backendRecipes)
            assertEquals(listOf("Gofry"), it.backendTitles)
            assertEquals("Zapisano", it.backendMessage)
            assertTrue(it.candidates.isEmpty())
        }
        server.takeRequest()
        assertEquals("""{"action":"NEW"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun resolveUpdateIsIdempotent() = runTest {
        respond(200, done); respond(200, done)
        repeat(2) { assertEquals(CaptureStates.DONE, (inbox.resolve(cid, 0, Resolution.update(rid)) as ApiResult.Ok).value.status) }
        repeat(2) { assertEquals("""{"action":"UPDATE","recipeId":"$rid"}""", server.takeRequest().body.readUtf8()) }
        assertEquals(CaptureStates.DONE, store.get(cid)!!.state.backendStatus)
    }

    @Test fun recipeGoneReloadsCandidates() = runTest {
        respond(409, """{"error":"recipe_gone","details":["deleted"]}""")
        respond(200, needsDecision.replace("\"needs_decision\"", "\"needs_decision\",\"reason\":\"TARGET_GONE\""))
        val r = inbox.resolve(cid, 0, Resolution.update(rid))
        assertEquals(ApiErrors.RECIPE_GONE, (r as ApiResult.Failed).error)
        server.takeRequest()
        assertEquals("/v1/captures/$cid", server.takeRequest().path)
        assertEquals("TARGET_GONE", store.get(cid)!!.state.backendReason)
        assertEquals("TARGET_GONE", inbox.items.value.single().reason)
    }

    @Test fun alreadyResolvedReloadsToo() = runTest {
        respond(409, """{"error":"already_resolved"}""")
        respond(200, done)
        assertEquals(ApiErrors.ALREADY_RESOLVED, (inbox.resolve(cid, 0, Resolution.new()) as ApiResult.Failed).error)
        assertEquals(CaptureStates.DONE, store.get(cid)!!.state.backendStatus)
    }

    @Test fun resolveAllAsNewSkipsStoredDecisions() = runTest {
        val two = """{"captureId":"$cid","status":"needs_decision","candidates":[
            {"index":0,"title":"Gofry","resolution":"NEW"},{"index":1,"title":"Wafle"},{"index":2,"title":"Racuchy"}]}"""
        respond(200, """{"captureId":"$cid","status":"needs_decision","candidates":[{"index":0,"resolution":"NEW"},{"index":1,"resolution":"NEW"},{"index":2}]}""")
        respond(200, done)
        val view = com.gatar.recipedealer.api.ApiJson.decodeFromString(com.gatar.recipedealer.api.CaptureView.serializer(), two)
        val r = inbox.resolveAllAsNew(view)
        assertEquals(CaptureStates.DONE, (r as ApiResult.Ok).value.status)
        listOf(1, 2).forEach { i ->
            server.takeRequest().let {
                assertEquals("/v1/captures/$cid/extracted/$i/resolution", it.path)
                assertEquals("""{"action":"NEW"}""", it.body.readUtf8())
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun resolveAllAsNewStopsOnFailure() = runTest {
        respond(503, "")
        val view = com.gatar.recipedealer.api.CaptureView(cid, CaptureStates.NEEDS_DECISION,
            candidates = listOf(com.gatar.recipedealer.api.ExtractedCandidate(0), com.gatar.recipedealer.api.ExtractedCandidate(1)))
        assertTrue(inbox.resolveAllAsNew(view) is ApiResult.Retry)
        assertEquals(1, server.requestCount)
    }

    @Test fun llmFailuresAreInTheInboxAndCanBeReread() = runTest {
        val rejected = """{"captureId":"$cid","status":"needs_attention","reason":"LLM_REQUEST_REJECTED",
            "message":"Automatic extraction was refused: bad image"}"""
        respond(200, """[$rejected,{"captureId":"other-phone","status":"needs_attention","reason":"LLM_UNAVAILABLE"}]""")
        respond(202, """{"captureId":"$cid","status":"processing"}""")
        inbox.refresh()
        assertEquals(listOf(cid, "other-phone"), inbox.items.value.map { it.captureId })
        assertTrue(inbox.items.value.all { Inbox.canReread(it.status, it.reason) })

        val r = inbox.forceLlm(cid)
        assertEquals(CaptureStates.PROCESSING, (r as ApiResult.Ok).value.status)
        assertEquals(listOf("other-phone"), inbox.items.value.map { it.captureId })
        store.get(cid)!!.state.let {
            assertEquals(CaptureStates.PROCESSING, it.backendStatus)
            assertEquals(null, it.backendReason)
        }
        server.takeRequest()
        server.takeRequest().let { assertEquals("POST", it.method); assertEquals("/v1/captures/$cid/llm-extraction", it.path) }
    }

    @Test fun rereadOnlyForFailedExtraction() {
        assertTrue(Inbox.canReread(CaptureStates.NEEDS_ATTENTION, Inbox.AWAITING_EXTRACTION))
        assertTrue(Inbox.canReread(CaptureStates.NEEDS_ATTENTION, Inbox.PROCESSING_FAILED))
        assertTrue(!Inbox.canReread(CaptureStates.NEEDS_ATTENTION, Inbox.TARGET_GONE))
        assertTrue(!Inbox.canReread(CaptureStates.NEEDS_DECISION, null))
        assertTrue(!Inbox.canReread(CaptureStates.DONE, null))
    }

    @Test fun noBackend() = runTest {
        val local = Inbox(store) { null }
        assertEquals(Inbox.NO_BACKEND, local.refresh())
        assertEquals(Inbox.NO_BACKEND, local.resolve(cid, 0, Resolution.new()))
    }
}
