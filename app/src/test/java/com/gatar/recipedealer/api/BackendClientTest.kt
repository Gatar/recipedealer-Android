package com.gatar.recipedealer.api

import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.Recipe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Every backend call against a mock server: request shape and parsing of every response shape. */
class BackendClientTest {
    private lateinit var server: MockWebServer
    private val cid = "5408aea6-4419-484e-9194-fc7455bd4b00"
    private val rid = "7d1c2f0e-0b7a-4a51-9a6e-1c2d3e4f5a6b"
    private val lockId = "0c9f1e7a-3b2d-4c5e-8f9a-112233445566"

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()

    private fun client(token: String? = "secret") = BackendClient(server.url("/api").toString(), token)

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).apply { headers.forEach { addHeader(it.first, it.second) } })

    private fun RecordedRequest.assertCall(method: String, path: String) {
        assertEquals(method, this.method)
        assertEquals(path, this.path)
        assertEquals("Bearer secret", getHeader("Authorization"))
    }

    private val recipeJson = """{"title":"Gofry belgijskie","language":"pl","times":{"totalMinutes":30},
        "ingredientGroups":[{"items":["mąka","mleko"]}],"stepGroups":[{"name":"Ciasto","items":["Wymieszaj."]}],
        "notes":[],"images":["https://x/g.jpg"],"author":"Ania"}"""

    // --- captures ---

    @Test fun captureViewNeedsDecisionFull() = runTest {
        respond(200, """{"captureId":"$cid","status":"needs_decision","reason":"TARGET_GONE","message":"Wybierz ponownie",
            "recipes":[],"candidates":[{"index":0,"title":"Gofry belgijskie","ingredientCount":10,"stepCount":7,
            "needsReview":true,"urls":["https://kwestiasmaku.com/przepis/gofry-belgijskie"],"resolution":"UPDATE",
            "targetRecipeId":"$rid"}],"receivedAt":"2026-10-22T19:21:04.795133Z","capturedAt":"2026-10-22T19:20:00Z",
            "sourceType":"WEB","sourceUrl":"https://kwestiasmaku.com/przepis/gofry-belgijskie","title":"Gofry belgijskie",
            "futureField":1}""")
        val v = client().capture(cid).valueOrNull()!!
        server.takeRequest().apply { assertCall("GET", "/api/v1/captures/$cid"); assertEquals("application/json", getHeader("Accept")) }
        assertEquals(CaptureStates.NEEDS_DECISION, v.status)
        assertEquals("TARGET_GONE", v.reason)
        assertEquals(ExtractedCandidate(0, "Gofry belgijskie", 10, 7, true,
            listOf("https://kwestiasmaku.com/przepis/gofry-belgijskie"), "UPDATE", rid), v.candidates.single())
        assertEquals("WEB", v.sourceType)
        assertEquals("2026-10-22T19:21:04.795133Z", v.receivedAt)
    }

    @Test fun captureViewMinimalAndDone() = runTest {
        respond(200, """{"captureId":"$cid","status":"accepted"}""")
        respond(200, """{"captureId":"$cid","status":"done","recipes":[{"recipeId":"$rid","title":"Gofry"}],"message":"1 przepis"}""")
        client().capture(cid).valueOrNull()!!.let {
            assertEquals(CaptureView(cid, "accepted"), it)
            assertTrue(it.candidates.isEmpty())
        }
        client().capture(cid).valueOrNull()!!.let {
            assertEquals(listOf(RecipeRef(rid, "Gofry")), it.recipes)
            assertEquals("1 przepis", it.message)
            assertNull(it.reason)
        }
    }

    @Test fun captureCandidateWithOptionalFieldsOmitted() = runTest {
        respond(200, """{"captureId":"$cid","status":"needs_decision","candidates":[{"index":2}]}""")
        assertEquals(ExtractedCandidate(2), client().capture(cid).valueOrNull()!!.candidates.single())
    }

    @Test fun captureNotFound() = runTest {
        respond(404, """{"error":"not_found"}""")
        assertEquals(ApiResult.Failed(404, ApiErrors.NOT_FOUND), client().capture(cid))
    }

    @Test fun inbox() = runTest {
        respond(200, """[{"captureId":"a","status":"needs_decision"},{"captureId":"b","status":"needs_attention",
            "reason":"PROCESSING_FAILED","message":"Nie udało się"}]""")
        respond(200, "[]")
        respond(400, """{"error":"invalid_request","details":["unknown status: foo"]}""")
        val list = client().captures().valueOrNull()!!
        assertEquals(listOf("a", "b"), list.map { it.captureId })
        assertEquals("PROCESSING_FAILED", list[1].reason)
        server.takeRequest().assertCall("GET", "/api/v1/captures?status=needs_decision%2Cneeds_attention")
        assertEquals(emptyList<CaptureView>(), client().captures(listOf("done")).valueOrNull())
        assertEquals("/api/v1/captures?status=done", server.takeRequest().path)
        assertEquals(ApiResult.Failed(400, ApiErrors.INVALID_REQUEST, listOf("unknown status: foo")),
            client().captures(listOf("foo")))
    }

    @Test fun resolveNew() = runTest {
        respond(200, """{"captureId":"$cid","status":"done","recipes":[{"recipeId":"$rid","title":"Gofry"}]}""")
        val r = client().resolve(cid, 0, Resolution.new())
        assertEquals(CaptureStates.DONE, r.valueOrNull()!!.status)
        server.takeRequest().apply {
            assertCall("PUT", "/api/v1/captures/$cid/extracted/0/resolution")
            assertTrue(getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals("""{"action":"NEW"}""", body.readUtf8())
        }
    }

    @Test fun resolveUpdateAndIdempotentRepeat() = runTest {
        val done = """{"captureId":"$cid","status":"done","recipes":[{"recipeId":"$rid","title":"Gofry"}]}"""
        respond(200, done); respond(200, done)
        repeat(2) { assertEquals(listOf(RecipeRef(rid, "Gofry")), client().resolve(cid, 1, Resolution.update(rid)).valueOrNull()!!.recipes) }
        repeat(2) {
            server.takeRequest().apply {
                assertCall("PUT", "/api/v1/captures/$cid/extracted/1/resolution")
                assertEquals("""{"action":"UPDATE","recipeId":"$rid"}""", body.readUtf8())
            }
        }
    }

    @Test fun resolveErrors() = runTest {
        respond(409, """{"error":"recipe_gone","details":["recipe $rid was deleted"]}""")
        respond(409, """{"error":"already_resolved","details":["candidate 0 was applied as NEW"]}""")
        respond(400, """{"error":"invalid_request","details":["UPDATE needs recipeId"]}""")
        respond(404, """{"error":"not_found"}""")
        assertEquals(ApiResult.Failed(409, ApiErrors.RECIPE_GONE, listOf("recipe $rid was deleted")), client().resolve(cid, 0, Resolution.update(rid)))
        assertEquals(ApiErrors.ALREADY_RESOLVED, (client().resolve(cid, 0, Resolution.update(rid)) as ApiResult.Failed).error)
        assertEquals(ApiErrors.INVALID_REQUEST, (client().resolve(cid, 0, Resolution(Resolution.UPDATE)) as ApiResult.Failed).error)
        assertEquals(ApiResult.Failed(404, ApiErrors.NOT_FOUND), client().resolve(cid, 9, Resolution.new()))
    }

    @Test fun forceLlmExtraction() = runTest {
        respond(202, """{"captureId":"$cid","status":"processing"}""")
        val r = client().forceLlmExtraction(cid) as ApiResult.Ok
        assertEquals(202, r.code)
        assertEquals(CaptureStates.PROCESSING, r.value.status)
        server.takeRequest().apply {
            assertCall("POST", "/api/v1/captures/$cid/llm-extraction")
            assertEquals(0L, bodySize)
        }
    }

    @Test fun forceLlmExtractionErrors() = runTest {
        respond(409, """{"error":"extraction_in_progress","details":["capture is being processed"]}""")
        respond(409, """{"error":"no_llm_input"}""")
        respond(503, """{"error":"llm_unavailable","details":["no Claude API key"]}""")
        respond(503, "")
        respond(404, "")
        assertEquals(ApiErrors.EXTRACTION_IN_PROGRESS, (client().forceLlmExtraction(cid) as ApiResult.Failed).error)
        assertEquals(ApiResult.Failed(409, ApiErrors.NO_LLM_INPUT), client().forceLlmExtraction(cid))
        // no API key on the server: an answer for the user, not a reason to retry
        assertEquals(ApiResult.Failed(503, ApiErrors.LLM_UNAVAILABLE, listOf("no Claude API key")), client().forceLlmExtraction(cid))
        assertEquals(ApiResult.Retry("HTTP 503"), client().forceLlmExtraction(cid))
        assertEquals(ApiResult.Failed(404, null), client().forceLlmExtraction(cid))
    }

    @Test fun deleteCapture() = runTest {
        respond(204)
        respond(409, """{"error":"capture_applied","details":["delete the recipe instead"]}""")
        assertEquals(ApiResult.Ok(Unit, 204), client().deleteCapture(cid))
        server.takeRequest().assertCall("DELETE", "/api/v1/captures/$cid")
        assertEquals(ApiResult.Failed(409, ApiErrors.CAPTURE_APPLIED, listOf("delete the recipe instead")), client().deleteCapture(cid))
    }

    // --- recipes ---

    @Test fun candidates() = runTest {
        respond(200, """[{"recipeId":"$rid","title":"Gofry belgijskie","score":1.0,"matchReason":"URL",
            "sourceSummary":"kwestiasmaku.com","thumbnailBlob":"abc","updatedAt":"2026-10-22T19:21:04Z"},
            {"recipeId":"r2","title":"Gofry","score":0.52,"matchReason":"TITLE"}]""")
        respond(200, "[]")
        val list = client().recipeCandidates("Gofry belgijskie", "https://kwestiasmaku.com/przepis/gofry-belgijskie").valueOrNull()!!
        assertEquals(RecipeMatch(rid, "Gofry belgijskie", 1.0, "URL", "kwestiasmaku.com", "abc", "2026-10-22T19:21:04Z"), list[0])
        assertEquals(RecipeMatch("r2", "Gofry", 0.52, "TITLE"), list[1])
        server.takeRequest().apply {
            assertEquals("GET", method)
            assertEquals("/api/v1/recipes/candidates", requestUrl!!.encodedPath)
            assertEquals("Gofry belgijskie", requestUrl!!.queryParameter("title"))
            assertEquals("https://kwestiasmaku.com/przepis/gofry-belgijskie", requestUrl!!.queryParameter("url"))
        }
        client().recipeCandidates(null, " ")
        assertEquals("/api/v1/recipes/candidates", server.takeRequest().path)
    }

    @Test fun syncPageWithLiveRecipeAndTombstones() = runTest {
        respond(200, """{"items":[
            {"recipeId":"$rid","updatedAt":"2026-10-22T19:21:04.795133Z","deleted":false,"headRevisionId":"h1",
             "needsReview":true,"recipe":$recipeJson,"sourceSummary":"kwestiasmaku.com","thumbnailBlob":"abc"},
            {"recipeId":"r2","updatedAt":"2026-10-22T19:22:00Z","deleted":true},
            {"recipeId":"r3","updatedAt":"2026-10-22T19:23:00Z","deleted":false,"mergedInto":"$rid"}],
            "nextCursor":"MTc5","hasMore":true}""")
        val page = client().sync().valueOrNull()!!
        assertEquals("/api/v1/recipes", server.takeRequest().path)
        assertEquals("MTc5", page.nextCursor)
        assertTrue(page.hasMore)
        val (live, deleted, merged) = page.items
        assertFalse(live.removed)
        assertEquals("h1", live.headRevisionId)
        assertTrue(live.needsReview)
        assertEquals("Gofry belgijskie", RecipeJson.decode(live.recipe!!).title)
        assertTrue(deleted.removed)
        assertTrue(merged.removed)
        assertEquals(rid, merged.mergedInto)
    }

    @Test fun syncParamsAndEmptyFeed() = runTest {
        respond(200, """{"items":[],"hasMore":false}""")
        respond(200, """{"items":[],"nextCursor":"MTc5","hasMore":false}""")
        respond(400, """{"error":"invalid_request","details":["malformed cursor"]}""")
        assertNull(client().sync(limit = 500).valueOrNull()!!.nextCursor)
        assertEquals("/api/v1/recipes?limit=500", server.takeRequest().path)
        client().sync(updatedSince = "2026-10-22T19:21:04Z")
        assertEquals("2026-10-22T19:21:04Z", server.takeRequest().requestUrl!!.queryParameter("updatedSince"))
        assertEquals(ApiErrors.INVALID_REQUEST, (client().sync(cursor = "bad") as ApiResult.Failed).error)
        assertEquals("/api/v1/recipes?cursor=bad", server.takeRequest().path)
    }

    @Test fun recipeDetailWithEtagAndConditionalGet() = runTest {
        respond(200, """{"recipeId":"$rid","headRevisionId":"h1","origin":"MACHINE","needsReview":false,"recipe":$recipeJson,
            "sourceSummary":"kwestiasmaku.com","thumbnailBlob":"abc","urls":["https://kwestiasmaku.com/przepis/gofry"],
            "createdAt":"2026-10-20T10:00:00Z","updatedAt":"2026-10-22T19:21:04Z",
            "lock":{"lockedBy":"Ania (Pixel 9)","expiresAt":"2026-10-22T19:30:00Z"}}""", "ETag" to "\"h1\"")
        respond(304)
        val ok = client().recipe(rid) as ApiResult.Ok
        server.takeRequest().apply { assertCall("GET", "/api/v1/recipes/$rid"); assertNull(getHeader("If-None-Match")) }
        assertEquals("\"h1\"", ok.etag)
        assertEquals("h1", ok.value.headRevisionId)
        assertEquals(Origins.MACHINE, ok.value.origin)
        assertEquals(LockInfo("Ania (Pixel 9)", "2026-10-22T19:30:00Z"), ok.value.lock)
        assertEquals(listOf("https://kwestiasmaku.com/przepis/gofry"), ok.value.urls)
        assertEquals(listOf(Group("Ciasto", listOf("Wymieszaj."))), RecipeJson.decode(ok.value.recipe).stepGroups)

        assertEquals(ApiResult.NotModified, client().recipe(rid, etag = "\"h1\""))
        assertEquals("\"h1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun recipeDetailMinimalAndGone() = runTest {
        respond(200, """{"recipeId":"$rid","headRevisionId":"h1","recipe":{}}""")
        respond(410, """{"error":"recipe_merged","details":["merged into r9"]}""")
        respond(410, """{"error":"recipe_deleted"}""")
        client().recipe(rid).valueOrNull()!!.let { assertNull(it.lock); assertNull(it.thumbnailBlob); assertEquals(Recipe(), RecipeJson.decode(it.recipe)) }
        assertEquals(ApiResult.Failed(410, ApiErrors.RECIPE_MERGED, listOf("merged into r9")), client().recipe(rid))
        assertEquals(ApiResult.Failed(410, ApiErrors.RECIPE_DELETED), client().recipe(rid))
    }

    @Test fun revisions() = runTest {
        respond(200, """[{"revisionId":"r-new","parentId":"h1","head":false,"origin":"MACHINE","needsReview":true,
            "runId":"run1","captureId":"$cid","contentHash":"c2","createdAt":"2026-10-22T19:25:00Z","recipe":$recipeJson},
            {"revisionId":"h1","head":true,"origin":"USER","userName":"Ania","createdAt":"2026-10-21T10:00:00Z","recipe":{"title":"G"}}]""")
        val list = client().revisions(rid).valueOrNull()!!
        server.takeRequest().assertCall("GET", "/api/v1/recipes/$rid/revisions")
        assertEquals(listOf("r-new", "h1"), list.map { it.revisionId })
        assertFalse(list[0].head)
        assertEquals(cid, list[0].captureId)
        assertNull(list[0].userName)
        assertTrue(list[1].head)
        assertEquals("Ania", list[1].userName)
        assertNull(list[1].parentId)
        assertEquals("G", RecipeJson.decode(list[1].recipe).title)
    }

    @Test fun search() = runTest {
        respond(200, """[{"recipeId":"$rid","title":"Gołąbki","score":0.83,"sourceSummary":"aniagotuje.pl","thumbnailBlob":"abc",
            "updatedAt":"2026-10-22T19:21:04Z"},{"recipeId":"r2","title":"Gofry"}]""")
        val hits = client().search("golabki").valueOrNull()!!
        server.takeRequest().assertCall("GET", "/api/v1/recipes/search?q=golabki")
        assertEquals(SearchHit(rid, "Gołąbki", 0.83, "aniagotuje.pl", "abc", "2026-10-22T19:21:04Z"), hits[0])
        assertEquals(SearchHit("r2", "Gofry"), hits[1])
    }

    @Test fun blob() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3)))
            .addHeader("Content-Type", "image/jpeg").addHeader("ETag", "\"abc\""))
        respond(404, """{"error":"not_found"}""")
        assertEquals(listOf<Byte>(1, 2, 3), client().blob("abc").valueOrNull()!!.toList())
        server.takeRequest().assertCall("GET", "/api/v1/blobs/abc")
        assertEquals(ApiResult.Failed(404, ApiErrors.NOT_FOUND), client().blob("abc"))
    }

    // --- editing ---

    @Test fun lockAcquireAndLockedShape() = runTest {
        respond(201, """{"lockId":"$lockId","expiresAt":"2026-10-22T19:30:00Z"}""")
        respond(409, """{"error":"locked","lockedBy":"Ania (Pixel 9)","expiresAt":"2026-10-22T19:30:00Z"}""")
        respond(410, """{"error":"recipe_gone"}""")
        val ok = client().lock(rid) as ApiResult.Ok
        assertEquals(201, ok.code)
        assertEquals(EditLock(lockId, "2026-10-22T19:30:00Z"), ok.value)
        server.takeRequest().apply { assertCall("POST", "/api/v1/recipes/$rid/lock"); assertEquals(0L, bodySize) }
        assertEquals(ApiResult.Locked("Ania (Pixel 9)", "2026-10-22T19:30:00Z"), client().lock(rid))
        assertEquals(ApiResult.Failed(410, ApiErrors.RECIPE_GONE), client().lock(rid))
    }

    @Test fun heartbeatAndLostLock() = runTest {
        respond(200, """{"lockId":"$lockId","expiresAt":"2026-10-22T19:35:00Z"}""")
        respond(409, """{"error":"lock_expired"}""")
        assertEquals(EditLock(lockId, "2026-10-22T19:35:00Z"), client().renewLock(rid, lockId).valueOrNull())
        server.takeRequest().assertCall("PUT", "/api/v1/recipes/$rid/lock/$lockId")
        assertEquals(ApiResult.Failed(409, ApiErrors.LOCK_EXPIRED), client().renewLock(rid, lockId))
    }

    @Test fun releaseLock() = runTest {
        respond(204); respond(204)
        repeat(2) { assertEquals(ApiResult.Ok(Unit, 204), client().releaseLock(rid, lockId)) }
        server.takeRequest().assertCall("DELETE", "/api/v1/recipes/$rid/lock/$lockId")
    }

    @Test fun saveRevisionHeadersBodyAndResults() = runTest {
        val view = """{"recipeId":"$rid","headRevisionId":"h2","origin":"USER","recipe":$recipeJson}"""
        respond(201, view, "ETag" to "\"h2\"")
        respond(200, view, "ETag" to "\"h2\"")
        val original = ApiJson.parseToJsonElement(recipeJson).jsonObject
        val edited = RecipeJson.decode(original).copy(title = "Gofry", notes = listOf("Na gorąco"))
        val payload = RecipeJson.merge(original, edited)
        val created = client().saveRevision(rid, lockId, "\"h1\"", payload) as ApiResult.Ok
        assertEquals(201, created.code)
        assertEquals("\"h2\"", created.etag)
        assertEquals(Origins.USER, created.value.origin)
        server.takeRequest().apply {
            assertCall("POST", "/api/v1/recipes/$rid/revisions")
            assertEquals(lockId, getHeader("Lock-Id"))
            assertEquals("\"h1\"", getHeader("If-Match"))
            assertTrue(getHeader("Content-Type")!!.startsWith("application/json"))
            val sent = ApiJson.parseToJsonElement(body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("Gofry"), sent["title"])
            assertEquals(JsonPrimitive("Ania"), sent["author"])   // a key the app does not model survives the edit
            assertEquals("Na gorąco", RecipeJson.decode(sent).notes.single())
        }
        assertEquals(200, (client().saveRevision(rid, lockId, "\"h1\"", payload) as ApiResult.Ok).code)
    }

    @Test fun saveRevisionErrors() = runTest {
        respond(400, """{"error":"invalid_recipe","details":["$.ingredientGroups[0].items: expected array"]}""")
        respond(409, """{"error":"lock_required"}""")
        respond(409, """{"error":"locked","lockedBy":"Tomek (Pixel 8)","expiresAt":"2026-10-22T19:30:00Z"}""")
        respond(412, """{"error":"head_changed","details":["head is h3"]}""")
        respond(428, """{"error":"if_match_required"}""")
        respond(410, """{"error":"recipe_gone"}""")
        val body = RecipeJson.merge(ApiJson.parseToJsonElement("{}").jsonObject, Recipe(title = "X"))
        suspend fun save() = client().saveRevision(rid, lockId, "\"h1\"", body)
        assertEquals(ApiResult.Failed(400, ApiErrors.INVALID_RECIPE, listOf("$.ingredientGroups[0].items: expected array")), save())
        assertEquals(ApiResult.Failed(409, ApiErrors.LOCK_REQUIRED), save())
        assertEquals(ApiResult.Locked("Tomek (Pixel 8)", "2026-10-22T19:30:00Z"), save())
        assertEquals(ApiResult.Failed(412, ApiErrors.HEAD_CHANGED, listOf("head is h3")), save())
        assertEquals(ApiResult.Failed(428, ApiErrors.IF_MATCH_REQUIRED), save())
        assertEquals(ApiResult.Failed(410, ApiErrors.RECIPE_GONE), save())
    }

    @Test fun deleteRecipe() = runTest {
        respond(204); respond(204); respond(412, """{"error":"head_changed"}""")
        assertEquals(ApiResult.Ok(Unit, 204), client().deleteRecipe(rid, lockId, "\"h1\""))
        server.takeRequest().apply {
            assertCall("DELETE", "/api/v1/recipes/$rid")
            assertEquals(lockId, getHeader("Lock-Id"))
            assertEquals("\"h1\"", getHeader("If-Match"))
        }
        assertEquals(ApiResult.Ok(Unit, 204), client().deleteRecipe(rid, lockId))
        assertNull(server.takeRequest().getHeader("If-Match"))
        assertEquals(ApiResult.Failed(412, ApiErrors.HEAD_CHANGED), client().deleteRecipe(rid, lockId))
    }

    // --- accounts ---

    private val session = """{"token":"rd_new","userId":"u1","deviceId":"d1","userName":"Ania"}"""

    @Test fun loginAndRegisterWithoutToken() = runTest {
        respond(201, session); respond(201, session)
        val s = client().login(LoginRequest("ania", "pw", "Pixel 9")).valueOrNull()!!
        assertEquals(Session("rd_new", "u1", "d1", "Ania"), s)
        server.takeRequest().apply {
            assertEquals("POST", method)
            assertEquals("/api/v1/auth/login", path)
            assertNull(getHeader("Authorization"))
            assertEquals("""{"userName":"ania","password":"pw","deviceName":"Pixel 9"}""", body.readUtf8())
        }
        assertTrue(client().register(RegisterRequest("Ania", "pw", "Tab", "kod")) is ApiResult.Ok)
        server.takeRequest().apply {
            assertEquals("/api/v1/auth/register", path)
            assertNull(getHeader("Authorization"))
            assertEquals("""{"userName":"Ania","password":"pw","deviceName":"Tab","registrationCode":"kod"}""", body.readUtf8())
        }
    }

    @Test fun setPasswordOmitsMissingCurrent() = runTest {
        respond(204); respond(204)
        assertEquals(ApiResult.Ok(Unit, 204), client().setPassword(PasswordChange("new")))
        server.takeRequest().apply {
            assertCall("PUT", "/api/v1/me/password")
            assertEquals("""{"password":"new"}""", body.readUtf8())
        }
        client().setPassword(PasswordChange("new", "old"))
        assertEquals("""{"password":"new","currentPassword":"old"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun accountErrorsAreAnswersNotDeadTokens() = runTest {
        var rejected = 0
        val c = BackendClient(server.url("/api").toString(), "secret", onUnauthorized = { rejected++ })
        respond(401, """{"error":"invalid_credentials","details":["x"]}""")
        respond(403, """{"error":"registration_disabled"}""")
        respond(403, """{"error":"invalid_credentials"}""")
        respond(429, """{"error":"too_many_requests"}""")
        respond(401, """{"error":"unauthorized"}""")
        assertEquals(ApiResult.Failed(401, ApiErrors.INVALID_CREDENTIALS, listOf("x")), c.login(LoginRequest("a", "b", "c")))
        assertEquals(ApiResult.Failed(403, ApiErrors.REGISTRATION_DISABLED), c.register(RegisterRequest("a", "b", "c", "d")))
        assertEquals(ApiResult.Failed(403, ApiErrors.INVALID_CREDENTIALS), c.setPassword(PasswordChange("n", "o")))
        assertEquals(ApiResult.Failed(429, ApiErrors.TOO_MANY_REQUESTS), c.login(LoginRequest("a", "b", "c")))
        assertEquals(0, rejected)
        assertEquals(ApiResult.Unauthorized(401), c.capture(cid))
        assertEquals(1, rejected)
    }

    // --- common ---

    @Test fun authRetryAndNetworkErrors() = runTest {
        respond(401); respond(403); respond(500); respond(429)
        assertEquals(ApiResult.Unauthorized(401), client().capture(cid))
        assertEquals(ApiResult.Unauthorized(403), client().sync())
        assertTrue(client().search("x") is ApiResult.Retry)
        assertTrue(client().captures() is ApiResult.Retry)
        server.shutdown()
        assertTrue(client().recipe(rid) is ApiResult.Retry)
    }

    @Test fun noTokenNoAuthorizationAndGarbageBody() = runTest {
        respond(200, "<html>proxy</html>")
        assertTrue(client(token = "").capture(cid) is ApiResult.Failed)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun mergeKeepsUnknownKeysAndDropsClearedOnes() {
        val original = ApiJson.parseToJsonElement("""{"title":"A","servings":{"text":"4"},"author":"Ania","extra":{"k":1}}""").jsonObject
        val merged = RecipeJson.merge(original, RecipeJson.decode(original).copy(servings = null, title = "B"))
        assertEquals(JsonPrimitive("B"), merged["title"])
        assertNull(merged["servings"])
        assertEquals(JsonPrimitive("Ania"), merged["author"])
        assertEquals(original["extra"], merged["extra"])
    }
}
