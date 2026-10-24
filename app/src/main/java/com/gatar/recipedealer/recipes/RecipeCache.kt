package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiJson
import com.gatar.recipedealer.api.LockInfo
import com.gatar.recipedealer.api.RecipeJson
import com.gatar.recipedealer.api.RecipeView
import com.gatar.recipedealer.api.etagOf
import com.gatar.recipedealer.api.SyncItem
import com.gatar.recipedealer.model.Recipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** One recipe of the offline cache: the sync feed item, plus what the last detail GET added. */
@Serializable
data class CachedRecipe(
    val recipeId: String,
    val updatedAt: String,
    val headRevisionId: String? = null,
    val needsReview: Boolean = false,
    val recipe: JsonObject,
    val sourceSummary: String? = null,
    val thumbnailBlob: String? = null,
    /** From `GET /v1/recipes/{id}`. */
    val urls: List<String> = emptyList(),
    val origin: String? = null,
    val lock: LockInfo? = null,
    /**
     * Head revision whose detail (urls, origin, lock) is stored. The feed brings the same head without those fields,
     * so a conditional GET may only use the ETag when this matches [headRevisionId] (else the 304 hides the urls).
     */
    val detailRevisionId: String? = null,
) {
    /** `If-None-Match` for the detail GET, or null when the detail of this head was never loaded. */
    val detailEtag: String? get() = detailRevisionId?.takeIf { it == headRevisionId }?.let(::etagOf)

    val title: String? get() = (recipe["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    /** `images[0]`: the page's photo or one the user set in the editor. */
    val imageUrl: String? get() = ((recipe["images"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    /** Changes whenever the shown photo changes (bitmap cache key). */
    val imageKey: String get() = imageUrl ?: thumbnailBlob ?: recipeId
    fun content(): Recipe = RecipeJson.decode(recipe)
}

@Serializable
private data class CacheFile(
    /** `nextCursor` of the last sync page; null → the next sync is a full one. */
    val cursor: String? = null,
    val recipes: List<CachedRecipe> = emptyList(),
    /** Merged recipe id → the recipe it went into (links keep working). */
    val redirects: Map<String, String> = emptyMap(),
)

/**
 * Offline recipe list: one JSON file (a household has hundreds of recipes, not millions). The recipe list screen
 * reads only this, so it works offline.
 */
class RecipeCache(private val file: File) {
    private var data: CacheFile = runCatching { ApiJson.decodeFromString(CacheFile.serializer(), file.readText()) }
        .getOrDefault(CacheFile())
    private val _recipes = MutableStateFlow(data.recipes)
    val recipes: StateFlow<List<CachedRecipe>> = _recipes.asStateFlow()

    val cursor: String? get() = data.cursor
    fun get(id: String): CachedRecipe? = data.recipes.firstOrNull { it.recipeId == id }
    /** Where a merged recipe went (following chains); null when [id] was not merged. */
    fun redirect(id: String): String? {
        var target = data.redirects[id] ?: return null
        repeat(10) { target = data.redirects[target] ?: return target }
        return target
    }

    /** Applies one feed page and stores its cursor; returns how many recipes changed. */
    @Synchronized
    fun apply(items: List<SyncItem>, cursor: String?): Int {
        val byId = data.recipes.associateBy { it.recipeId }.toMutableMap()
        val redirects = data.redirects.toMutableMap()
        var changed = 0
        for (item in items) {
            val old = byId[item.recipeId]
            if (item.removed) {
                if (byId.remove(item.recipeId) != null) changed++
                item.mergedInto?.let { redirects[item.recipeId] = it }
                continue
            }
            val new = CachedRecipe(
                recipeId = item.recipeId,
                updatedAt = item.updatedAt,
                headRevisionId = item.headRevisionId,
                needsReview = item.needsReview,
                recipe = item.recipe!!,
                sourceSummary = item.sourceSummary,
                thumbnailBlob = item.thumbnailBlob,
                urls = old?.urls.orEmpty(),
                origin = old?.origin,
                lock = old?.lock,
                detailRevisionId = old?.detailRevisionId,
            )
            if (new != old) changed++
            byId[item.recipeId] = new
            redirects.remove(item.recipeId)
        }
        write(data.copy(cursor = cursor, recipes = byId.values.toList(), redirects = redirects))
        return changed
    }

    /** After a full sync: recipes the feed did not list any more are gone. */
    @Synchronized
    fun retainOnly(ids: Set<String>): Int {
        val kept = data.recipes.filter { it.recipeId in ids }
        val removed = data.recipes.size - kept.size
        if (removed > 0) write(data.copy(recipes = kept))
        return removed
    }

    @Synchronized
    fun clearCursor() = write(data.copy(cursor = null))

    /** A fresh `GET /v1/recipes/{id}` (or the result of a save). */
    @Synchronized
    fun putDetail(view: RecipeView) {
        val old = get(view.recipeId)
        val new = CachedRecipe(
            recipeId = view.recipeId,
            updatedAt = view.updatedAt ?: old?.updatedAt.orEmpty(),
            headRevisionId = view.headRevisionId,
            needsReview = view.needsReview,
            recipe = view.recipe,
            sourceSummary = view.sourceSummary,
            thumbnailBlob = view.thumbnailBlob,
            urls = view.urls,
            origin = view.origin,
            lock = view.lock,
            detailRevisionId = view.headRevisionId,
        )
        write(data.copy(recipes = data.recipes.filterNot { it.recipeId == view.recipeId } + new))
    }

    /** 410 from the detail GET, or a delete. */
    @Synchronized
    fun remove(id: String, mergedInto: String? = null) {
        write(data.copy(
            recipes = data.recipes.filterNot { it.recipeId == id },
            redirects = mergedInto?.let { data.redirects + (id to it) } ?: data.redirects,
        ))
    }

    private fun write(d: CacheFile) {
        data = d
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiJson.encodeToString(CacheFile.serializer(), d))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _recipes.value = d.recipes
    }
}
