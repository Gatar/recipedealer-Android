package com.gatar.recipedealer.web

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gatar.recipedealer.capture.TextResult
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real check of the extractor: each fixture in a real (off-screen) WebView, merged like in the app, must give
 * the same recipe and provenance as the JVM golden built from the jsdom output.
 */
@RunWith(AndroidJUnit4::class)
class ExtractorWebViewTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val settleMs = mapOf("spa-delayed" to 3000L, "mutating" to 1500L)

    private fun asset(path: String) = testContext.assets.open(path).bufferedReader().readText()

    @Test fun fixturesMatchGoldens() = runBlocking {
        val failures = mutableListOf<String>()
        val names = testContext.assets.list("fixtures")!!.filter { it.endsWith(".html") || it.endsWith(".txt") }.sorted()
        for (file in names) {
            val name = file.substringBeforeLast('.')
            val output = withContext(Dispatchers.Main) { extract(name, asset("fixtures/$file"), file.endsWith(".txt")) }
            if (output == null) { failures += "$name: no output"; continue }
            val e = if (file.endsWith(".txt")) TextResult.extraction(asset("fixtures/$file"), output)
                else WebResult.extraction(output, LoadInfo("https://example.test/$name"))
            val golden = CaptureJson.parseToJsonElement(asset("expected-recipe/$name.json")).jsonObject
            val expectedRecipe = CaptureJson.decodeFromJsonElement(Recipe.serializer(), golden["recipe"]!!)
            val expectedProv = golden["provenance"]!!.jsonObject.mapValues { it.value.toString().trim('"') }
            val recipe = com.gatar.recipedealer.capture.RecipeCleanup.finish(e.recipe)
            if (recipe != expectedRecipe) failures += "$name recipe:\n  webview: $recipe\n  golden:  $expectedRecipe"
            if (e.provenance != expectedProv) failures += "$name provenance: ${e.provenance} vs $expectedProv"
        }
        assertEquals(15, names.size)
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
    }

    private suspend fun extract(name: String, content: String, isText: Boolean): JsonObject? {
        val webView = offscreenWebView(appContext)
        try {
            val page = PageController(webView, ExtractorScripts(appContext))
            val html = if (isText) "<!DOCTYPE html><html><head></head><body></body></html>" else content
            webView.loadDataWithBaseURL("https://example.test/$name", html, "text/html", "utf-8", null)
            page.loads.receive()
            delay(settleMs[name] ?: 300L)
            return if (isText) page.runOnText(content) else page.runExtractor()
        } finally {
            webView.destroy()
        }
    }

    @Suppress("unused")
    private fun keep(w: WebView) = w
}
