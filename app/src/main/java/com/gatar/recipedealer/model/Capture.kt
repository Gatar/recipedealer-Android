package com.gatar.recipedealer.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// The capture contract (schemaVersion 1, schema/capture-v1.json). Property order here is the JSON key order.
// An incompatible change means a new schemaVersion, not an edit.

const val SCHEMA_VERSION = 1

@OptIn(ExperimentalSerializationApi::class)
val CaptureJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Serializable
data class Capture(
    val schemaVersion: Int = SCHEMA_VERSION,
    val captureId: String,
    val capturedAt: String,
    val client: ClientInfo,
    val share: ShareInfo,
    val source: Source,
    val recipe: Recipe = Recipe(),
    val completeness: Completeness = Completeness.of(Recipe(), emptyMap()),
    val provenance: Map<String, String> = emptyMap(),
    val raw: Raw = Raw(),
    val attachments: List<Attachment> = emptyList(),
    val diagnostics: Diagnostics = Diagnostics(),
)

@Serializable
data class ClientInfo(val appVersion: String, val androidSdk: Int, val deviceModel: String)

@Serializable
data class ShareInfo(
    val action: String? = null,
    val mimeType: String? = null,
    val sourceApp: String? = null,
    val subject: String? = null,
    val title: String? = null,
)

@Serializable
enum class SourceType { WEB, TEXT, IMAGE, PDF, MANUAL }

@Serializable
data class Source(
    val type: SourceType,
    val url: String? = null,
    val siteName: String? = null,
    val author: String? = null,
    val files: List<String> = emptyList(),
)

@Serializable
data class Recipe(
    val title: String? = null,
    val language: String? = null,
    val servings: Servings? = null,
    val times: Times = Times(),
    val ingredientGroups: List<Group> = emptyList(),
    val stepGroups: List<Group> = emptyList(),
    val notes: List<String> = emptyList(),
    val images: List<String> = emptyList(),
) {
    val hasIngredients get() = ingredientGroups.any { it.items.isNotEmpty() }
    val hasSteps get() = stepGroups.any { it.items.isNotEmpty() }
}

@Serializable
data class Servings(val text: String, val count: Int? = null)

/** Never computed from each other: sources are inconsistent. */
@Serializable
data class Times(val prepMinutes: Int? = null, val cookMinutes: Int? = null, val totalMinutes: Int? = null)

@Serializable
data class Group(val name: String? = null, val items: List<String>)

@Serializable
data class Completeness(
    val score: Double,
    val hasTitle: Boolean,
    val hasIngredients: Boolean,
    val hasSteps: Boolean,
    val bestStage: String,
) {
    companion object {
        /** Title 0.2, ingredients 0.4, steps 0.4. `bestStage`: the stage that gave the ingredients or steps. */
        fun of(recipe: Recipe, provenance: Map<String, String>): Completeness {
            val hasTitle = !recipe.title.isNullOrBlank()
            val score = (if (hasTitle) 2 else 0) + (if (recipe.hasIngredients) 4 else 0) + (if (recipe.hasSteps) 4 else 0)
            val best = provenance["ingredients"] ?: provenance["steps"] ?: provenance["title"] ?: "none"
            return Completeness(score / 10.0, hasTitle, recipe.hasIngredients, recipe.hasSteps, best)
        }
    }
}

@Serializable
data class Raw(
    val text: String? = null,
    val web: WebRaw? = null,
    val pdfText: String? = null,
    val ocr: OcrRaw? = null,
)

@Serializable
data class WebRaw(
    val finalUrl: String? = null,
    val canonicalUrl: String? = null,
    val title: String? = null,
    val lang: String? = null,
    val siteName: String? = null,
    val ogImage: String? = null,
    val jsonLdRecipeId: String? = null,
    val botChallenge: String? = null,
    val stabilized: Boolean = false,
    val renderTimeMs: Long? = null,
    val readableText: String? = null,
    val extractor: JsonElement? = null,
)

@Serializable
enum class OcrSource { IMAGE, PDF_RENDER, WEBVIEW_SNAPSHOT }

@Serializable
data class OcrRaw(
    val source: OcrSource,
    val text: String,
    val lines: List<OcrLine> = emptyList(),
    val truncated: Boolean = false,
)

/** `page`: 0-based image / PDF page / tile index; `bbox`: [left, top, right, bottom] in source pixels. */
@Serializable
data class OcrLine(val text: String, val page: Int, val bbox: List<Int>)

@Serializable
data class Attachment(
    val name: String,
    val role: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
) {
    companion object {
        const val ROLE_IMAGE = "image"
        const val ROLE_PDF = "pdf"
        const val ROLE_SCREENSHOT = "screenshot"
    }
}

@Serializable
data class Diagnostics(
    val timingsMs: Map<String, Long> = emptyMap(),
    val errors: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** Provenance keys and stage names used in `provenance` / `completeness.bestStage`. */
object Stage {
    const val JSON_LD = "jsonLd"
    const val MICRODATA = "microdata"
    const val PLUGIN = "pluginHits"
    const val HEURISTIC = "heuristic"
    const val OCR = "ocr"
    const val PAGE = "page"
    const val MANUAL = "manual"
    const val USER = "user"
}
