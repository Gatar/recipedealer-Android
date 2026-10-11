package com.gatar.recipedealer.capture

import com.gatar.recipedealer.model.Raw
import com.gatar.recipedealer.web.ExtractorMerge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared text structured by the extractor (`loadText` + `run`) → the TEXT part of a capture. */
object TextResult {
    fun extraction(text: String, output: JsonObject?, timingsMs: Map<String, Long> = emptyMap(), errors: List<String> = emptyList()): Extraction {
        val cleaned = TextCleaner.clean(text)
        if (output == null) return Extraction(raw = Raw(text = cleaned), timingsMs = timingsMs, errors = errors)
        val merged = ExtractorMerge.merge(output)
        val extractorErrors = (output["errors"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }.map { "extractor: $it" }
        return Extraction(
            recipe = merged.recipe.copy(language = LanguageGuess.of(merged.recipe)),
            provenance = merged.provenance,
            raw = Raw(text = cleaned),
            timingsMs = timingsMs,
            errors = errors + extractorErrors,
        )
    }
}
