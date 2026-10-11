package com.gatar.recipedealer.web

import com.gatar.recipedealer.model.Group
import com.gatar.recipedealer.model.Recipe
import com.gatar.recipedealer.model.Servings
import com.gatar.recipedealer.model.Stage
import com.gatar.recipedealer.model.Times
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One stage's view of the recipe, already normalized. */
data class Candidate(
    val stage: String,
    val title: String? = null,
    val servings: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val ingredients: List<Group> = emptyList(),
    val steps: List<Group> = emptyList(),
    val notes: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val author: String? = null,
)

data class MergeResult(
    val recipe: Recipe,
    val provenance: Map<String, String>,
    val author: String?,
    val jsonLdRecipeId: String?,
)

/**
 * Merges the seed extractor's output (JSON-LD → microdata → plugin selectors → heading heuristic) field by field
 * with provenance (final-app.md §6.3 step 5). Pure Kotlin: unit-tested against the jsdom fixture outputs.
 */
object ExtractorMerge {
    private val PLUGINS = listOf("wprm", "tasty", "mediavine")

    fun merge(output: JsonObject): MergeResult {
        val ld = JsonLd.findRecipe(output["jsonLd"])
        val candidates = listOfNotNull(
            ld?.let { schemaCandidate(Stage.JSON_LD, it) },
            output["microdata"].array().firstOrNull()?.obj()?.let { schemaCandidate(Stage.MICRODATA, it) },
            pluginCandidate(output["pluginHits"].obj()),
            heuristicCandidate(output["heuristic"].obj()),
        )
        val prov = linkedMapOf<String, String>()
        fun <T : Any> pick(key: String, get: (Candidate) -> T?): T? {
            for (c in candidates) {
                val v = get(c) ?: continue
                if (v is String && v.isBlank()) continue
                if (v is Collection<*> && v.isEmpty()) continue
                prov[key] = c.stage
                return v
            }
            return null
        }
        val title = pick("title") { it.title }
        val ingredients = pickGroups("ingredients", candidates, prov) { it.ingredients }
        val steps = pickGroups("steps", candidates, prov) { it.steps }
        val servings = pick("servings") { it.servings }
        val prep = pick("prepMinutes") { it.prepMinutes }
        val cook = pick("cookMinutes") { it.cookMinutes }
        val total = pick("totalMinutes") { it.totalMinutes }
        var images = pick("images") { it.images.takeIf(List<String>::isNotEmpty) }
        if (images == null) {
            images = output.str("ogImage")?.let { listOf(it) }?.also { prov["images"] = Stage.PAGE }
        }
        val notes = pick("notes") { it.notes.takeIf(List<String>::isNotEmpty) }
        val author = pick("author") { it.author }
        val recipe = Recipe(
            title = title,
            servings = servings?.let { Servings(it, servingsCount(it)) },
            times = Times(prep, cook, total),
            ingredientGroups = ingredients,
            stepGroups = steps,
            notes = notes.orEmpty(),
            images = dedupeImages(images.orEmpty()),
        )
        return MergeResult(recipe, prov, author, ld?.let { JsonLd.id(it) })
    }

    /**
     * First stage with items wins. Group rule: a flat winning list is replaced by a lower stage's named groups with
     * the same item count (JSON-LD ingredients are always flat, WPRM markup has groups).
     */
    private fun pickGroups(key: String, cs: List<Candidate>, prov: MutableMap<String, String>, get: (Candidate) -> List<Group>): List<Group> {
        val i = cs.indexOfFirst { c -> get(c).any { it.items.isNotEmpty() } }
        if (i < 0) return emptyList()
        var win = get(cs[i])
        var stage = cs[i].stage
        if (win.all { it.name == null }) {
            val n = win.sumOf { it.items.size }
            cs.drop(i + 1).firstOrNull { c -> get(c).any { it.name != null } && get(c).sumOf { it.items.size } == n }?.let {
                win = get(it)
                stage = it.stage
            }
        }
        prov[key] = stage
        return win
    }

    // ---- stages ------------------------------------------------------------------------------------------------

    /** JSON-LD Recipe node or a microdata item (the extractor gives both schema.org shaped). */
    private fun schemaCandidate(stage: String, r: JsonObject) = Candidate(
        stage = stage,
        title = text(r["name"]),
        servings = yieldText(r["recipeYield"] ?: r["yield"]),
        prepMinutes = Durations.iso(text(r["prepTime"])),
        cookMinutes = Durations.iso(text(r["cookTime"])),
        totalMinutes = Durations.iso(text(r["totalTime"])),
        ingredients = flatGroup((r["recipeIngredient"] ?: r["ingredients"]).array().mapNotNull(::text)),
        steps = JsonLd.instructions(r["recipeInstructions"]),
        images = JsonLd.images(r["image"]),
        author = JsonLd.names(r["author"]),
    )

    private fun pluginCandidate(hits: JsonObject?): Candidate? {
        val r = PLUGINS.firstNotNullOfOrNull { name ->
            hits?.get(name).obj()?.takeIf { (it["found"] as? JsonPrimitive)?.booleanOrNull == true }?.get("recipe").obj()
        } ?: return null
        return Candidate(
            stage = Stage.PLUGIN,
            title = text(r["name"]),
            servings = text(r["recipeYield"])?.let(::stripLabel),
            prepMinutes = Durations.text(text(r["prepTime"])),
            cookMinutes = Durations.text(text(r["cookTime"])),
            totalMinutes = Durations.text(text(r["totalTime"])),
            ingredients = groups(r["recipeIngredient"]),
            steps = groups(r["recipeInstructions"]),
            notes = r["notes"].array().mapNotNull(::text),
            images = listOfNotNull(text(r["image"])),
        )
    }

    private fun heuristicCandidate(h: JsonObject?): Candidate? {
        h ?: return null
        return Candidate(
            stage = Stage.HEURISTIC,
            title = h.str("recipeHeading"),
            servings = h.str("servings"),
            ingredients = groups(h["ingredients"]),
            steps = groups(h["instructions"]),
            notes = h["notes"].array().mapNotNull(::text),
        )
    }

    // ---- normalization -----------------------------------------------------------------------------------------

    /** `[{name, items}]` or a flat list of strings → groups, cleaned. */
    fun groups(el: JsonElement?): List<Group> {
        val arr = el.array()
        if (arr.all { it is JsonPrimitive }) return flatGroup(arr.mapNotNull(::text))
        return arr.mapNotNull { g ->
            val o = g.obj() ?: return@mapNotNull null
            Group(groupName(text(o["name"])), o["items"].array().mapNotNull(::text).map(::cleanItem).filter(String::isNotEmpty))
        }.filter { it.items.isNotEmpty() }
    }

    private fun flatGroup(items: List<String>): List<Group> =
        items.map(::cleanItem).filter(String::isNotEmpty).takeIf { it.isNotEmpty() }?.let { listOf(Group(null, it)) }.orEmpty()

    /** `▢` checklist marks, " ," → ",". */
    fun cleanItem(s: String): String = Text.norm(s.replace("▢", "").replace(Regex("\\s+,"), ","))

    /** Trailing colons in group names. */
    fun groupName(s: String?): String? = s?.let { Text.norm(it).trimEnd(':', '：', ' ') }?.takeIf { it.isNotEmpty() }

    private fun stripLabel(s: String) = s.replace(Regex("^(servings|porcje|porcji|ilość porcji|yield)\\s*:?\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun yieldText(el: JsonElement?): String? {
        val values = (if (el is JsonArray) el.toList() else listOfNotNull(el)).mapNotNull(::text)
        // ["4", "4 porcje"]: the most descriptive one
        return values.maxByOrNull { it.length }
    }

    private val SERVINGS = Regex(
        "^(\\d{1,3})\\s*(porcj\\w*|os[oó]b\\w*|osoby|servings?|people|persons?|portions?)?\\.?$", RegexOption.IGNORE_CASE)

    /** Only when the text is clearly a number of servings ("12 porcji", "4", "2 servings"; not "4-6", "1 blacha"). */
    fun servingsCount(text: String): Int? {
        val t = text.trim().replace(Regex("^(na|for|serves|dla)\\s+", RegexOption.IGNORE_CASE), "")
        return SERVINGS.matchEntire(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Distinct image URLs, ignoring the query string. */
    fun dedupeImages(urls: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return urls.map(String::trim).filter { it.isNotEmpty() && seen.add(it.substringBefore('?').substringBefore('#')) }
    }

    internal fun text(el: JsonElement?): String? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> el.contentOrNull?.let(Text::norm)?.takeIf { it.isNotEmpty() }
        is JsonArray -> el.firstNotNullOfOrNull(::text)
        is JsonObject -> text(el["text"] ?: el["name"] ?: el["@value"])
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.array(): List<JsonElement> = when (this) {
    is JsonArray -> this
    null, JsonNull -> emptyList()
    else -> listOf(this)
}
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
