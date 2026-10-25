package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiJson
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.Tag
import com.gatar.recipedealer.api.TagCatalog
import com.gatar.recipedealer.api.TagCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** The tag catalog (`GET /v1/tags`), kept in a file so the tag column and the filter work offline. */
class TagCatalogStore(private val file: File) {
    private val _catalog = MutableStateFlow(
        runCatching { ApiJson.decodeFromString(TagCatalog.serializer(), file.readText()) }.getOrDefault(TagCatalog()))
    val catalog: StateFlow<TagCatalog> = _catalog.asStateFlow()

    /** A backend without tags (404) counts as an empty catalog, not as a failure. */
    suspend fun refresh(client: BackendClient): ApiResult<TagCatalog> {
        val r = client.tags()
        return when {
            r is ApiResult.Ok -> { put(r.value); r }
            r is ApiResult.Failed && r.code == 404 -> ApiResult.Ok(catalog.value)
            else -> r
        }
    }

    @Synchronized
    fun put(catalog: TagCatalog) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiJson.encodeToString(TagCatalog.serializer(), catalog))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _catalog.value = catalog
    }
}

/** A category of the tag column with the tags at least one recipe carries, and how many recipes carry each. */
data class UsedCategory(val category: TagCategory, val tags: List<Pair<Tag, Int>>)

object TagFilter {
    /** Only categories and tags some recipe carries, in catalog order (unknown ids are ignored). */
    fun used(catalog: TagCatalog, recipes: List<CachedRecipe>): List<UsedCategory> {
        val counts = recipes.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount()
        return catalog.categories.mapNotNull { c ->
            val tags = c.tags.mapNotNull { t -> counts[t.id]?.let { t to it } }
            if (tags.isEmpty()) null else UsedCategory(c, tags)
        }
    }

    /** All selected categories must match (AND); within one category any selected tag is enough (OR). */
    fun matches(tags: Collection<String>, selected: Set<String>): Boolean =
        selected.groupBy { it.substringBefore(':') }.values.all { group -> group.any { it in tags } }

    /** Readable name for a title: "Danie główne" → "danie-glowne" (same rule as the backend). */
    fun slug(title: String): String =
        Normalizer.normalize(title.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trimEnd('-')

    val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
}
