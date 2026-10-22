package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiJson
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.RecipeView
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecipeSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private lateinit var cache: RecipeCache
    private lateinit var sync: RecipeSync

    @Before fun start() {
        server = MockWebServer().apply { start() }
        file = File(tmp.newFolder("recipes"), "recipes.json")
        cache = RecipeCache(file)
        sync = RecipeSync(cache, { BackendClient(server.url("/").toString(), "secret") }, pageSize = 2)
    }

    @After fun stop() = server.shutdown()

    private fun page(items: String, cursor: String?, hasMore: Boolean) = server.enqueue(MockResponse().setResponseCode(200)
        .setBody("""{"items":[$items],${cursor?.let { "\"nextCursor\":\"$it\"," } ?: ""}"hasMore":$hasMore}"""))

    private fun live(id: String, title: String, head: String = "h-$id", at: String = "2026-10-22T10:00:00Z") =
        """{"recipeId":"$id","updatedAt":"$at","deleted":false,"headRevisionId":"$head","needsReview":false,
            "recipe":{"title":"$title","ingredientGroups":[{"items":["x"]}],"author":"A"},"sourceSummary":"site.pl","thumbnailBlob":"blob-$id-0000000000"}"""

    private fun deleted(id: String) = """{"recipeId":"$id","updatedAt":"2026-10-22T11:00:00Z","deleted":true}"""
    private fun merged(id: String, into: String) = """{"recipeId":"$id","updatedAt":"2026-10-22T11:00:00Z","deleted":false,"mergedInto":"$into"}"""

    @Test fun firstSyncPagesWhileHasMoreAndPersistsCursor() = runTest {
        page(live("a", "Gofry") + "," + live("b", "Bigos"), "c1", hasMore = true)
        page(live("c", "Chłodnik"), "c2", hasMore = false)
        assertEquals(ApiResult.Ok(3), sync.sync())
        assertEquals("/v1/recipes?limit=2", server.takeRequest().path)
        assertEquals("/v1/recipes?cursor=c1&limit=2", server.takeRequest().path)
        assertEquals(setOf("Gofry", "Bigos", "Chłodnik"), cache.recipes.value.map { it.title }.toSet())

        // survives a restart
        val reloaded = RecipeCache(file)
        assertEquals("c2", reloaded.cursor)
        assertEquals(3, reloaded.recipes.value.size)
        reloaded.get("a")!!.let {
            assertEquals("h-a", it.headRevisionId)
            assertEquals("site.pl", it.sourceSummary)
            assertEquals("A", it.recipe["author"].toString().trim('"'))
            assertEquals(listOf("x"), it.content().ingredientGroups.single().items)
        }
    }

    @Test fun incrementalSyncUsesCursorAndAppliesTombstones() = runTest {
        page(live("a", "Gofry") + "," + live("b", "Bigos"), "c1", hasMore = false)
        sync.sync()
        server.takeRequest()
        page(live("a", "Gofry belgijskie", head = "h2") + "," + deleted("b"), "c2", hasMore = false)
        assertEquals(ApiResult.Ok(2), sync.sync())
        assertEquals("/v1/recipes?cursor=c1&limit=2", server.takeRequest().path)
        assertEquals(listOf("Gofry belgijskie"), cache.recipes.value.map { it.title })
        assertEquals("c2", cache.cursor)
    }

    @Test fun mergedTombstoneRemovesAndRedirects() = runTest {
        page(live("a", "Gofry") + "," + live("b", "Gofry 2"), "c1", hasMore = false)
        sync.sync()
        page(merged("b", "a"), "c2", hasMore = false)
        sync.sync()
        assertNull(cache.get("b"))
        assertEquals("a", cache.redirect("b"))
        assertEquals("a", RecipeCache(file).redirect("b"))
    }

    @Test fun unchangedFeedKeepsCursorAndCountsNothing() = runTest {
        page(live("a", "Gofry"), "c1", hasMore = false)
        sync.sync()
        page("", "c1", hasMore = false)
        assertEquals(ApiResult.Ok(0), sync.sync())
        assertEquals("c1", cache.cursor)
    }

    @Test fun invalidCursorFallsBackToFullSyncAndDropsStaleRecipes() = runTest {
        page(live("a", "Gofry") + "," + live("b", "Bigos"), "c1", hasMore = false)
        sync.sync()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request","details":["malformed cursor"]}"""))
        page(live("a", "Gofry"), "c9", hasMore = false)   // "b" vanished while the cursor was broken
        assertTrue(sync.sync() is ApiResult.Ok)
        assertEquals("/v1/recipes?cursor=c1&limit=2", server.takeRequest().path)
        assertEquals("/v1/recipes?limit=2", server.takeRequest().path)
        assertEquals(listOf("a"), cache.recipes.value.map { it.recipeId })
        assertEquals("c9", cache.cursor)
    }

    @Test fun invalidCursorTwiceGivesUp() = runTest {
        page(live("a", "Gofry"), "c1", hasMore = false)
        sync.sync()
        repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request"}""")) }
        assertTrue(sync.sync() is ApiResult.Failed)
        assertEquals(1, cache.recipes.value.size)
    }

    @Test fun offlineKeepsTheCache() = runTest {
        page(live("a", "Gofry"), "c1", hasMore = false)
        sync.sync()
        server.shutdown()
        assertTrue(sync.sync() is ApiResult.Retry)
        assertEquals("c1", cache.cursor)
        assertEquals(1, RecipeCache(file).recipes.value.size)
    }

    @Test fun detailKeepsEtagUntilTheHeadChanges() = runTest {
        page(live("a", "Gofry", head = "h1"), "c1", hasMore = false)
        sync.sync()
        val view = ApiJson.decodeFromString(RecipeView.serializer(),
            """{"recipeId":"a","headRevisionId":"h1","recipe":{"title":"Gofry"},"urls":["https://site.pl/gofry"],"updatedAt":"2026-10-22T10:00:00Z"}""")
        cache.putDetail(view, "\"h1\"")
        page(live("a", "Gofry", head = "h1"), "c2", hasMore = false)
        sync.sync()
        assertEquals("\"h1\"", cache.get("a")!!.etag)
        assertEquals(listOf("https://site.pl/gofry"), cache.get("a")!!.urls)
        page(live("a", "Gofry!", head = "h2"), "c3", hasMore = false)
        sync.sync()
        assertNull(cache.get("a")!!.etag)
        cache.remove("a")
        assertTrue(cache.recipes.value.isEmpty())
    }

    @Suppress("unused") private val keep = ApiJson.parseToJsonElement("{}").jsonObject
}
