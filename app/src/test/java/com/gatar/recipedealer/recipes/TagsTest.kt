package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiJson
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.CategoryChange
import com.gatar.recipedealer.api.Resolution
import com.gatar.recipedealer.api.TagCatalog
import com.gatar.recipedealer.api.TagChange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TagsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()

    private fun client() = BackendClient(server.url("/").toString(), "secret")
    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private val catalogJson = """{"categories":[
        {"name":"meal","title":"Posiłek","tags":[
            {"id":"meal:breakfast","name":"breakfast","title":"Śniadanie","recipeCount":0},
            {"id":"meal:dinner","name":"dinner","title":"Obiad","recipeCount":2},
            {"id":"meal:supper","name":"supper","title":"Kolacja","recipeCount":1}]},
        {"name":"diet","title":"Skład","tags":[{"id":"diet:meat","name":"meat","title":"Mięsne","recipeCount":1}]},
        {"name":"kind","title":"Rodzaj","tags":[{"id":"kind:soup","name":"soup","title":"Zupa"}]}]}"""
    private val catalog = ApiJson.decodeFromString(TagCatalog.serializer(), catalogJson)

    private fun recipe(id: String, vararg tags: String) =
        CachedRecipe(recipeId = id, updatedAt = "2026-10-25T10:00:00Z", recipe = JsonObject(emptyMap()), tags = tags.toList())

    @Test fun columnShowsOnlyUsedTagsInCatalogOrderWithLocalCounts() {
        val recipes = listOf(
            recipe("a", "meal:supper", "diet:meat"),
            recipe("b", "meal:dinner"),
            recipe("c", "meal:dinner", "meal:gone"),
        )
        val used = TagFilter.used(catalog, recipes)
        assertEquals(listOf("meal", "diet"), used.map { it.category.name })
        assertEquals(listOf("meal:dinner" to 2, "meal:supper" to 1), used[0].tags.map { it.first.id to it.second })
        assertTrue(TagFilter.used(catalog, emptyList()).isEmpty())
    }

    @Test fun filterIsAndAcrossCategoriesAndOrWithinOne() {
        val supperMeat = listOf("meal:supper", "diet:meat")
        val dinner = listOf("meal:dinner")
        assertTrue(TagFilter.matches(dinner, emptySet()))
        assertTrue(TagFilter.matches(dinner, setOf("meal:dinner", "meal:supper")))
        assertTrue(TagFilter.matches(supperMeat, setOf("meal:dinner", "meal:supper")))
        assertTrue(TagFilter.matches(supperMeat, setOf("meal:supper", "diet:meat")))
        assertFalse(TagFilter.matches(dinner, setOf("meal:dinner", "diet:meat")))
        assertFalse(TagFilter.matches(supperMeat, setOf("meal:dinner")))
    }

    @Test fun slugMatchesTheBackendRule() {
        assertEquals("danie-glowne", TagFilter.slug("Danie główne"))
        assertEquals("losos-zurek", TagFilter.slug("  Łosoś & Żurek!  "))
        assertTrue(TagFilter.NAME.matches("danie-glowne"))
        assertFalse(TagFilter.NAME.matches("meal:x"))
        assertFalse(TagFilter.NAME.matches("a--b"))
    }

    @Test fun catalogIsStoredAndA404MeansNoTags() = runTest {
        val file = tmp.newFile("tags.json").also { it.delete() }
        val store = TagCatalogStore(file)
        respond(200, catalogJson)
        assertTrue(store.refresh(client()) is ApiResult.Ok)
        assertEquals("/v1/tags", server.takeRequest().path)
        assertEquals(catalog, TagCatalogStore(file).catalog.value)
        assertEquals("Obiad", catalog.tags["meal:dinner"]!!.title)
        assertEquals("Skład", catalog.category("diet:meat")!!.title)

        respond(404, """{"error":"not_found"}""")
        assertEquals(ApiResult.Ok(catalog), store.refresh(client()))
        respond(500)
        assertTrue(store.refresh(client()) is ApiResult.Retry)
        assertEquals(catalog, store.catalog.value)
    }

    @Test fun catalogChangesSendOnlyTheChangedFields() = runTest {
        repeat(6) { respond(200, catalogJson) }
        respond(201, catalogJson)
        val c = client()
        c.createCategory(CategoryChange(name = "meal", title = "Posiłek"))
        c.updateCategory("meal", CategoryChange(position = 1))
        c.deleteCategory("kind")
        c.createTag("meal", TagChange(name = "brunch", title = "Brunch"))
        c.updateTag("meal", "supper", TagChange(category = "kind"))
        c.deleteTag("meal", "supper")
        assertEquals(catalog, c.tags().let { (it as ApiResult.Ok).value })
        fun next() = server.takeRequest().let { Triple(it.method, it.path, it.body.readUtf8()) }
        assertEquals(Triple("POST", "/v1/tags/categories", """{"name":"meal","title":"Posiłek"}"""), next())
        assertEquals(Triple("PATCH", "/v1/tags/categories/meal", """{"position":1}"""), next())
        assertEquals(Triple("DELETE", "/v1/tags/categories/kind", ""), next())
        assertEquals(Triple("POST", "/v1/tags/categories/meal/tags", """{"name":"brunch","title":"Brunch"}"""), next())
        assertEquals(Triple("PATCH", "/v1/tags/categories/meal/tags/supper", """{"category":"kind"}"""), next())
        assertEquals(Triple("DELETE", "/v1/tags/categories/meal/tags/supper", ""), next())
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun recipeTagsAndDecisionTags() = runTest {
        respond(200, """{"tags":["meal:dinner","kind:soup"]}""")
        val r = client().setRecipeTags("r1", listOf("kind:soup", "meal:dinner"))
        assertEquals(listOf("meal:dinner", "kind:soup"), (r as ApiResult.Ok).value.tags)
        server.takeRequest().let {
            assertEquals("PUT", it.method)
            assertEquals("/v1/recipes/r1/tags", it.path)
            assertEquals("""{"tags":["kind:soup","meal:dinner"]}""", it.body.readUtf8())
        }
        assertEquals("""{"action":"NEW","tags":["meal:dinner"]}""",
            ApiJson.encodeToString(Resolution.serializer(), Resolution.new(listOf("meal:dinner"))))
        assertEquals("""{"action":"NEW"}""", ApiJson.encodeToString(Resolution.serializer(), Resolution.new()))
        assertEquals("""{"action":"UPDATE","recipeId":"r1"}""",
            ApiJson.encodeToString(Resolution.serializer(), Resolution.update("r1")))
    }

    @Test fun syncBringsTagsAndRefreshesTheCatalogFirst() = runTest {
        val cache = RecipeCache(tmp.newFolder("recipes").resolve("recipes.json"))
        val store = TagCatalogStore(tmp.root.resolve("tags.json"))
        val sync = RecipeSync(cache, ::client, tags = store)
        respond(200, catalogJson)
        respond(200, """{"items":[{"recipeId":"a","updatedAt":"2026-10-25T10:00:00Z","headRevisionId":"h1",
            "recipe":{"title":"Rosół"},"tags":["meal:dinner","kind:soup"]}],"nextCursor":"c1","hasMore":false}""")
        assertEquals(ApiResult.Ok(1), sync.sync())
        assertEquals("/v1/tags", server.takeRequest().path)
        assertEquals(listOf("meal:dinner", "kind:soup"), cache.get("a")!!.tags)
        assertEquals(catalog, store.catalog.value)

        cache.setTags("a", listOf("diet:meat"))
        assertEquals(listOf("diet:meat"), RecipeCache(tmp.root.resolve("recipes/recipes.json")).get("a")!!.tags)
    }
}
