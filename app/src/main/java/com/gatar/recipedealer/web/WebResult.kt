package com.gatar.recipedealer.web

import com.gatar.recipedealer.capture.Extraction
import com.gatar.recipedealer.capture.LanguageGuess
import com.gatar.recipedealer.model.Raw
import com.gatar.recipedealer.model.WebRaw
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** How the page was loaded – what the WebView side knows besides the extractor output. */
data class LoadInfo(
    val sharedUrl: String?,
    val stabilized: Boolean = false,
    val renderTimeMs: Long? = null,
    val timingsMs: Map<String, Long> = emptyMap(),
    val notes: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

/** Extractor output (+ load info) → the WEB part of a capture. Pure, shared by the app and the golden tests. */
object WebResult {
    fun extraction(output: JsonObject, load: LoadInfo): Extraction {
        val merged = ExtractorMerge.merge(output)
        val lang = output.str("lang")
        val recipe = merged.recipe.copy(language = LanguageGuess.of(merged.recipe, lang))
        val canonical = output.str("canonicalUrl")?.takeIf { it.startsWith("http") }
        val final = output.str("finalUrl")?.takeIf { it.startsWith("http") }
        val extractorTimings = (output["timingsMs"] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.longOrNull?.let { "extractor.$k" to it }
        }.orEmpty().toMap()
        val extractorErrors = output["errors"].array().mapNotNull { (it as? JsonPrimitive)?.content }.map { "extractor: $it" }
        return Extraction(
            recipe = recipe,
            provenance = merged.provenance,
            raw = Raw(
                web = WebRaw(
                    finalUrl = final,
                    canonicalUrl = canonical,
                    title = output.str("title"),
                    lang = lang,
                    siteName = output.str("siteName"),
                    ogImage = output.str("ogImage"),
                    jsonLdRecipeId = merged.jsonLdRecipeId,
                    botChallenge = output.str("botChallenge"),
                    stabilized = load.stabilized,
                    renderTimeMs = load.renderTimeMs,
                    readableText = output.str("readableText"),
                    extractor = output,
                ),
            ),
            url = canonical ?: final ?: load.sharedUrl,
            siteName = output.str("siteName"),
            author = merged.author,
            timingsMs = load.timingsMs + extractorTimings,
            errors = load.errors + extractorErrors,
            notes = load.notes,
        )
    }
}
