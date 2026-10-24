package com.gatar.recipedealer.capture

import com.gatar.recipedealer.model.Capture
import com.gatar.recipedealer.model.ClientInfo
import com.gatar.recipedealer.model.Completeness
import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.Servings
import com.gatar.recipedealer.model.ShareInfo
import com.gatar.recipedealer.model.Source
import com.gatar.recipedealer.model.SourceType
import com.gatar.recipedealer.model.Stage
import com.gatar.recipedealer.model.Times
import com.gatar.recipedealer.web.ExtractorMerge
import kotlinx.serialization.Serializable

/** Form state of one group: its name and its items, one per line. */
@Serializable
data class GroupDraft(val name: String = "", val text: String = "")

/** What the editor (preview and manual entry) works on: plain strings, converted to the DTO on save. */
@Serializable
data class RecipeDraft(
    val title: String = "",
    val servings: String = "",
    val prepMinutes: String = "",
    val cookMinutes: String = "",
    val totalMinutes: String = "",
    val ingredientGroups: List<GroupDraft> = listOf(GroupDraft()),
    val stepGroups: List<GroupDraft> = listOf(GroupDraft()),
    val notes: String = "",
) {
    /** The edited recipe; [base] keeps what the form does not show (images, language). */
    fun toRecipe(base: Recipe = Recipe()): Recipe {
        val recipe = base.copy(
            title = title.trim().ifEmpty { null },
            servings = servings.trim().ifEmpty { null }?.let { Servings(it, ExtractorMerge.servingsCount(it)) },
            times = Times(minutes(prepMinutes), minutes(cookMinutes), minutes(totalMinutes)),
            ingredientGroups = groups(ingredientGroups),
            stepGroups = groups(stepGroups),
            notes = lines(notes),
        )
        return recipe.copy(language = LanguageGuess.of(recipe) ?: base.language)
    }

    /**
     * Same content as [other] for the user: whitespace around lines, blank lines and empty groups do not count
     * (they disappear in [toRecipe] anyway). Compared on the form, not on the recipe: [toRecipe] re-guesses the
     * language and the servings count, which would look like a change.
     */
    fun sameContentAs(other: RecipeDraft) = normalized() == other.normalized()

    private fun normalized() = RecipeDraft(
        title = text(title),
        servings = text(servings),
        prepMinutes = minutes(prepMinutes)?.toString().orEmpty(),
        cookMinutes = minutes(cookMinutes)?.toString().orEmpty(),
        totalMinutes = minutes(totalMinutes)?.toString().orEmpty(),
        ingredientGroups = normalizedGroups(ingredientGroups),
        stepGroups = normalizedGroups(stepGroups),
        notes = text(notes),
    )

    companion object {
        fun from(r: Recipe) = RecipeDraft(
            title = r.title.orEmpty(),
            servings = r.servings?.text.orEmpty(),
            prepMinutes = r.times.prepMinutes?.toString().orEmpty(),
            cookMinutes = r.times.cookMinutes?.toString().orEmpty(),
            totalMinutes = r.times.totalMinutes?.toString().orEmpty(),
            ingredientGroups = r.ingredientGroups.map { GroupDraft(it.name.orEmpty(), it.items.joinToString("\n")) }.ifEmpty { listOf(GroupDraft()) },
            stepGroups = r.stepGroups.map { GroupDraft(it.name.orEmpty(), it.items.joinToString("\n")) }.ifEmpty { listOf(GroupDraft()) },
            notes = r.notes.joinToString("\n"),
        )

        private fun lines(s: String) = s.lines().map(String::trim).filter(String::isNotEmpty)
        private fun text(s: String) = lines(s).joinToString("\n")
        private fun normalizedGroups(gs: List<GroupDraft>) =
            gs.map { GroupDraft(it.name.trim().trimEnd(':').trim(), text(it.text)) }.filter { it.text.isNotEmpty() }
        private fun minutes(s: String) = s.trim().toIntOrNull()?.takeIf { it > 0 }
        private fun groups(gs: List<GroupDraft>) =
            gs.map { Group(it.name.trim().trimEnd(':').trim().ifEmpty { null }, lines(it.text)) }.filter { it.items.isNotEmpty() }
    }
}

object RecipeEdits {
    /** Provenance key → value getter; a changed value is marked as edited by the user. */
    private val FIELDS: List<Pair<String, (Recipe) -> Any?>> = listOf(
        "title" to { r -> r.title },
        "servings" to { r -> r.servings },
        "prepMinutes" to { r -> r.times.prepMinutes },
        "cookMinutes" to { r -> r.times.cookMinutes },
        "totalMinutes" to { r -> r.times.totalMinutes },
        "ingredients" to { r -> r.ingredientGroups },
        "steps" to { r -> r.stepGroups },
        "notes" to { r -> r.notes },
    )

    /** The user corrected the recipe in the preview: raw material stays unchanged, edited fields get provenance `user`. */
    fun applyEdit(c: Capture, edited: Recipe): Capture {
        val recipe = RecipeCleanup.finish(edited)
        val prov = c.provenance.toMutableMap()
        for ((key, get) in FIELDS) {
            val before = get(c.recipe)
            val after = get(recipe)
            if (before == after) continue
            if (isEmpty(after)) prov.remove(key) else prov[key] = Stage.USER
        }
        return c.copy(recipe = recipe, provenance = prov, completeness = Completeness.of(recipe, prov))
    }

    /** Manual entry (decision D5): a `MANUAL` capture, every filled field from `manual`. */
    fun manualCapture(id: String, capturedAt: String, client: ClientInfo, recipe: Recipe): Capture {
        val r = RecipeCleanup.finish(recipe)
        val prov = FIELDS.filterNot { (_, get) -> isEmpty(get(r)) }.associate { (key, _) -> key to Stage.MANUAL }
        return Capture(
            captureId = id,
            capturedAt = capturedAt,
            client = client,
            share = ShareInfo(),
            source = Source(SourceType.MANUAL),
            recipe = r,
            completeness = Completeness.of(r, prov),
            provenance = prov,
        )
    }

    private fun isEmpty(v: Any?) = v == null || (v is Collection<*> && v.isEmpty())
}
