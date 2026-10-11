package com.gatar.recipedealer.web

import com.gatar.recipedealer.CaptureSchema
import com.gatar.recipedealer.TestFiles
import com.gatar.recipedealer.capture.CaptureBuilder
import com.gatar.recipedealer.capture.TextResult
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.CaptureJson
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.share.Route
import com.gatar.recipedealer.share.ShareRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden test of the whole resulting recipe per fixture: the real extractor ran in jsdom
 * (`tools/generate-extractor-fixtures.js` → `extractor-output/`), the merge runs here.
 * `./gradlew :app:testDebugUnitTest -PupdateGolden` rewrites `expected-recipe/`; review the git diff.
 */
class GoldenRecipeTest {
    private val outputs = TestFiles.resource("extractor-output").listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }

    @Test fun everyFixtureMatchesItsGolden() {
        val failures = mutableListOf<String>()
        for (file in outputs) {
            val name = file.nameWithoutExtension
            val capture = capture(name, CaptureJson.parseToJsonElement(file.readText()).jsonObject)
            assertEquals("$name: schema", emptyList<String>(), CaptureSchema.errors(CaptureJson.encodeToString(Capture.serializer(), capture)))
            val actual = CaptureJson.encodeToString(JsonObject.serializer(), golden(capture)) + "\n"
            val goldenFile = TestFiles.resource("expected-recipe/$name.json")
            if (TestFiles.updateGolden) {
                goldenFile.writeText(actual)
            } else if (!goldenFile.exists() || goldenFile.readText() != actual) {
                failures += "$name differs from its golden:\n$actual"
            }
        }
        assertEquals(outputs.size, 15)
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n\n"))
    }

    private fun capture(name: String, output: JsonObject): Capture {
        val isText = TestFiles.fixture("$name.txt").exists()
        val url = "https://example.test/$name"
        val request = ShareRequest("3f6c1a52-8a0e-4c5e-9a43-2a1f0d6b7c11", "2026-10-11T10:00:00Z", "android.intent.action.SEND",
            "text/plain", if (isText) TestFiles.fixture("$name.txt").readText() else url, null, null, null)
        val route = Route(if (isText) SourceType.TEXT else SourceType.WEB, if (isText) null else url, emptyList(), emptyList())
        val extraction = if (isText) TextResult.extraction(request.text!!, output) else WebResult.extraction(output, LoadInfo(url))
        return CaptureBuilder.build(request, route, extraction, ClientInfo("test", 36, "test"))
    }

    /** What the golden pins: the recipe and how it was found (raw material is the extractor's own output). */
    private fun golden(c: Capture) = buildJsonObject {
        put("source", CaptureJson.encodeToJsonElement(com.gatar.recipedealer.model.Source.serializer(), c.source))
        put("recipe", CaptureJson.encodeToJsonElement(com.gatar.recipedealer.model.Recipe.serializer(), c.recipe))
        put("provenance", JsonObject(c.provenance.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
        put("completeness", CaptureJson.encodeToJsonElement(com.gatar.recipedealer.model.Completeness.serializer(), c.completeness))
        c.raw.web?.jsonLdRecipeId?.let { put("jsonLdRecipeId", kotlinx.serialization.json.JsonPrimitive(it)) }
    }
}
