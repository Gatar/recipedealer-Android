package com.gatar.recipedealer.capture

import com.gatar.recipedealer.model.Attachment
import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.Completeness
import com.gatar.recipedealer.model.Diagnostics
import com.gatar.recipedealer.model.Raw
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.Source
import com.gatar.recipedealer.share.Route
import com.gatar.recipedealer.share.ShareRequest

/** What an on-device extractor produced for one share. */
data class Extraction(
    val recipe: Recipe = Recipe(),
    val provenance: Map<String, String> = emptyMap(),
    val raw: Raw = Raw(),
    val attachments: List<Attachment> = emptyList(),
    /** Better URL than the shared one (canonical → final); null keeps the shared URL. */
    val url: String? = null,
    val siteName: String? = null,
    val author: String? = null,
    val timingsMs: Map<String, Long> = emptyMap(),
    val errors: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

object CaptureBuilder {
    fun build(request: ShareRequest, route: Route, extraction: Extraction, client: ClientInfo): Capture {
        val recipe = RecipeCleanup.finish(extraction.recipe)
        return Capture(
            captureId = request.captureId,
            capturedAt = request.capturedAt,
            client = client,
            share = request.shareInfo,
            source = Source(
                type = route.type,
                url = extraction.url ?: route.url,
                siteName = extraction.siteName,
                author = extraction.author,
                files = route.files.mapIndexed { i, f -> f.displayName ?: "file-${i + 1}" },
            ),
            recipe = recipe,
            completeness = Completeness.of(recipe, extraction.provenance),
            provenance = extraction.provenance,
            raw = extraction.raw,
            attachments = extraction.attachments,
            diagnostics = Diagnostics(
                timingsMs = extraction.timingsMs,
                errors = extraction.errors,
                notes = route.notes + extraction.notes + request.extras.map { "extra: $it" },
            ),
        )
    }
}

/** Final, source-independent clean-ups of a recipe. */
object RecipeCleanup {
    fun finish(r: Recipe): Recipe = r.copy(
        title = r.title?.trim()?.takeIf { it.isNotEmpty() },
        ingredientGroups = r.ingredientGroups.map { g -> g.copy(items = g.items.map(String::trim).filter(String::isNotEmpty)) }
            .filter { it.items.isNotEmpty() },
        stepGroups = r.stepGroups.map { g -> g.copy(items = g.items.map(String::trim).filter(String::isNotEmpty)) }
            .filter { it.items.isNotEmpty() },
        notes = r.notes.map(String::trim).filter(String::isNotEmpty),
    )
}
