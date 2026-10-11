package com.gatar.recipedealer.model

import com.gatar.recipedealer.CaptureSchema
import com.gatar.recipedealer.TestFiles
import com.gatar.recipedealer.capture.CaptureBuilder
import com.gatar.recipedealer.capture.Extraction
import com.gatar.recipedealer.share.Route
import com.gatar.recipedealer.share.ShareRequest
import com.gatar.recipedealer.share.SharedFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every capture the app builds must validate against schema/capture-v1.json. */
class CaptureContractTest {
    private val client = ClientInfo("1.0.0", 36, "Pixel 9")

    private fun request(text: String? = null, files: List<SharedFile> = emptyList()) = ShareRequest(
        captureId = "3f6c1a52-8a0e-4c5e-9a43-2a1f0d6b7c11", capturedAt = "2026-10-09T17:42:05Z",
        action = "android.intent.action.SEND", mimeType = "text/plain", text = text, subject = null, title = null,
        referrer = "com.android.chrome", files = files)

    private fun assertValid(c: Capture) {
        val json = CaptureJson.encodeToString(Capture.serializer(), c)
        assertEquals(emptyList<String>(), CaptureSchema.errors(json))
    }

    @Test fun exampleFromTheContractIsValid() {
        val text = TestFiles.resource("contract/example-web-capture.json").readText()
        assertEquals(emptyList<String>(), CaptureSchema.errors(text))
        // and survives our model unchanged in substance
        val c = CaptureJson.decodeFromString(Capture.serializer(), text)
        assertValid(c)
        assertEquals("Szarlotka", c.recipe.title)
        assertEquals(12, c.recipe.servings?.count)
    }

    @Test fun keyOrderFollowsTheContract() {
        val c = CaptureBuilder.build(request("x"), Route(SourceType.TEXT, null, emptyList(), emptyList()), Extraction(), client)
        val keys = CaptureJson.parseToJsonElement(CaptureJson.encodeToString(Capture.serializer(), c)).jsonObject.keys.toList()
        assertEquals(listOf("schemaVersion", "captureId", "capturedAt", "client", "share", "source", "recipe",
            "completeness", "provenance", "raw", "attachments", "diagnostics"), keys)
    }

    @Test fun emptyCapturesOfEveryTypeAreValid() {
        SourceType.entries.forEach { type ->
            val files = if (type == SourceType.IMAGE || type == SourceType.PDF) listOf(SharedFile("/x/a", "image/png", "a.png")) else emptyList()
            val c = CaptureBuilder.build(request(files = files), Route(type, null, files, emptyList()), Extraction(), client)
            assertValid(c)
            assertEquals(type, c.source.type)
        }
    }

    @Test fun fullCaptureIsValid() {
        val recipe = Recipe(
            title = "Szarlotka", language = "pl", servings = Servings("12 porcji", 12), times = Times(30, 50, null),
            ingredientGroups = listOf(Group("Ciasto", listOf("320 g mąki")), Group(null, listOf("1 jajko"))),
            stepGroups = listOf(Group(null, listOf("Piec 50 minut."))), notes = listOf("Uwaga"), images = listOf("https://e/x.jpg"))
        val extraction = Extraction(
            recipe = recipe,
            provenance = mapOf("title" to Stage.JSON_LD, "ingredients" to Stage.HEURISTIC),
            raw = Raw(
                text = "t",
                web = WebRaw(finalUrl = "https://e/x", stabilized = true, renderTimeMs = 1200,
                    extractor = buildJsonObject { put("title", "x") }),
                pdfText = "a\u000Cb",
                ocr = OcrRaw(OcrSource.IMAGE, "x", listOf(OcrLine("x", 0, listOf(1, 2, 3, 4))), truncated = false),
            ),
            attachments = listOf(Attachment("image-1.jpg", Attachment.ROLE_IMAGE, "image/jpeg", 1200, 1600, 12345)),
            url = "https://e/x", siteName = "Site", author = "Ania",
            timingsMs = mapOf("stabilize" to 5000L), errors = listOf("e"), notes = listOf("n"),
        )
        val c = CaptureBuilder.build(request("https://e/x"), Route(SourceType.WEB, "https://e/x", emptyList(), emptyList()), extraction, client)
        assertValid(c)
        assertEquals(1.0, c.completeness.score, 0.0)
        assertEquals(Stage.HEURISTIC, c.completeness.bestStage)
    }

    @Test fun validatorRejectsBrokenCaptures() {
        val c = CaptureBuilder.build(request("x"), Route(SourceType.TEXT, null, emptyList(), emptyList()), Extraction(), client)
        val obj = CaptureJson.parseToJsonElement(CaptureJson.encodeToString(Capture.serializer(), c)).jsonObject
        fun broken(change: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit) =
            JsonObject(obj.toMutableMap().apply(change)).toString()
        assertTrue(CaptureSchema.errors(broken { remove("raw") }).isNotEmpty())
        assertTrue(CaptureSchema.errors(broken { put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(2)) }).isNotEmpty())
        assertTrue(CaptureSchema.errors(broken { put("captureId", kotlinx.serialization.json.JsonPrimitive("nope")) }).isNotEmpty())
        assertTrue(CaptureSchema.errors(broken { put("capturedAt", kotlinx.serialization.json.JsonPrimitive("yesterday")) }).isNotEmpty())
    }

    @Test fun completenessWeights() {
        assertEquals(0.6, Completeness.of(Recipe(title = "x", stepGroups = listOf(Group(null, listOf("a")))), emptyMap()).score, 1e-9)
        assertEquals(0.8, Completeness.of(Recipe(ingredientGroups = listOf(Group(null, listOf("a"))), stepGroups = listOf(Group(null, listOf("a")))), emptyMap()).score, 1e-9)
        assertEquals("none", Completeness.of(Recipe(), emptyMap()).bestStage)
    }
}
