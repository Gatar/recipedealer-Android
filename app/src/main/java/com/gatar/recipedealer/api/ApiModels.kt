package com.gatar.recipedealer.api

import com.gatar.recipedealer.model.Recipe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// Response / request bodies of the backend API (RecipeDealerBE). Responses omit null fields, so everything that is
// optional on the server has a default here. `recipe` objects stay JsonObject: the backend may carry keys the app's
// Recipe does not model, and an edit must not drop them (see RecipeJson.merge).

@OptIn(ExperimentalSerializationApi::class)
val ApiJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** Capture status values (`CaptureView.status`). */
object CaptureStates {
    const val ACCEPTED = "accepted"
    const val PROCESSING = "processing"
    const val NEEDS_DECISION = "needs_decision"
    const val NEEDS_ATTENTION = "needs_attention"
    const val DONE = "done"
}

/** `GET /v1/captures/{id}`, `GET /v1/captures?status=…`, the PUT upload and the resolution PUT. */
@Serializable
data class CaptureView(
    val captureId: String,
    val status: String,
    /** PROCESSING_FAILED | AWAITING_EXTRACTION | TARGET_GONE */
    val reason: String? = null,
    val message: String? = null,
    val recipes: List<RecipeRef> = emptyList(),
    /** Only while `needs_decision`. */
    val candidates: List<ExtractedCandidate> = emptyList(),
    val receivedAt: String? = null,
    val capturedAt: String? = null,
    val sourceType: String? = null,
    val sourceUrl: String? = null,
    val title: String? = null,
)

@Serializable
data class RecipeRef(val recipeId: String, val title: String? = null)

/** One recipe extracted from a capture, waiting for the user's NEW / UPDATE decision. */
@Serializable
data class ExtractedCandidate(
    val index: Int,
    val title: String? = null,
    val ingredientCount: Int = 0,
    val stepCount: Int = 0,
    val needsReview: Boolean = false,
    val urls: List<String> = emptyList(),
    /** NEW | UPDATE: a decision is already stored. */
    val resolution: String? = null,
    val targetRecipeId: String? = null,
)

/**
 * Body of `PUT /v1/captures/{id}/extracted/{index}/resolution`; [tags] (NEW only) go to the new recipe.
 * Absent when empty, so a backend without tags gets the old body.
 */
@Serializable
data class Resolution(val action: String, val recipeId: String? = null, val tags: List<String>? = null) {
    companion object {
        const val NEW = "NEW"
        const val UPDATE = "UPDATE"
        fun new(tags: List<String> = emptyList()) = Resolution(NEW, tags = tags.ifEmpty { null })
        fun update(recipeId: String) = Resolution(UPDATE, recipeId)
    }
}

/** `GET /v1/recipes/candidates`: possible existing recipes for an extracted one. */
@Serializable
data class RecipeMatch(
    val recipeId: String,
    val title: String? = null,
    val score: Double = 0.0,
    /** URL | TITLE */
    val matchReason: String? = null,
    val sourceSummary: String? = null,
    val thumbnailBlob: String? = null,
    val updatedAt: String? = null,
)

/** `GET /v1/recipes` sync feed page. */
@Serializable
data class SyncPage(val items: List<SyncItem> = emptyList(), val nextCursor: String? = null, val hasMore: Boolean = false)

/** A live recipe, or a tombstone (`deleted`, or `mergedInto` another recipe) without content fields. */
@Serializable
data class SyncItem(
    val recipeId: String,
    val updatedAt: String,
    val deleted: Boolean = false,
    val mergedInto: String? = null,
    val headRevisionId: String? = null,
    val needsReview: Boolean = false,
    val recipe: JsonObject? = null,
    val sourceSummary: String? = null,
    val thumbnailBlob: String? = null,
    /** Tag ids (`category:tag`) in catalog order. */
    val tags: List<String> = emptyList(),
) {
    val removed get() = deleted || mergedInto != null || recipe == null
}

/** `GET /v1/recipes/{id}` and the result of saving a revision. */
@Serializable
data class RecipeView(
    val recipeId: String,
    val headRevisionId: String,
    /** MACHINE | USER */
    val origin: String? = null,
    val needsReview: Boolean = false,
    val recipe: JsonObject,
    val sourceSummary: String? = null,
    val thumbnailBlob: String? = null,
    val urls: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Only while someone edits the recipe. */
    val lock: LockInfo? = null,
)

@Serializable
data class LockInfo(val lockedBy: String? = null, val expiresAt: String? = null)

/** `GET /v1/recipes/{id}/revisions`, newest first. */
@Serializable
data class RevisionView(
    val revisionId: String,
    val parentId: String? = null,
    val head: Boolean = false,
    val origin: String? = null,
    val needsReview: Boolean = false,
    val runId: String? = null,
    val captureId: String? = null,
    val userName: String? = null,
    val contentHash: String? = null,
    val createdAt: String? = null,
    val recipe: JsonObject,
)

/** `GET /v1/recipes/search`. */
@Serializable
data class SearchHit(
    val recipeId: String,
    val title: String? = null,
    val score: Double = 0.0,
    val sourceSummary: String? = null,
    val thumbnailBlob: String? = null,
    val updatedAt: String? = null,
)

/** `GET /v1/tags` and the answer of every catalog change: categories and tags in the user's order. */
@Serializable
data class TagCatalog(val categories: List<TagCategory> = emptyList()) {
    val tags: Map<String, Tag> get() = categories.flatMap { it.tags }.associateBy { it.id }
    fun category(tagId: String): TagCategory? = categories.firstOrNull { it.name == tagId.substringBefore(':') }
}

/** [name] is the stable id (`meal`), [title] what the app shows ("Posiłek"). */
@Serializable
data class TagCategory(val name: String, val title: String, val tags: List<Tag> = emptyList())

/** [id] = `category:name`; [recipeCount]: live recipes carrying it (server side). */
@Serializable
data class Tag(val id: String, val name: String, val title: String, val recipeCount: Int = 0)

/** Create / change a category; null fields stay unchanged, [position] is 0-based. */
@Serializable
data class CategoryChange(val name: String? = null, val title: String? = null, val position: Int? = null)

/** Create / change a tag; [category] moves it. */
@Serializable
data class TagChange(val name: String? = null, val title: String? = null, val category: String? = null, val position: Int? = null)

/** `PUT /v1/recipes/{id}/tags` body and answer. */
@Serializable
data class RecipeTags(val tags: List<String>)

/** `POST /v1/recipes/{id}/lock` and its heartbeat. */
@Serializable
data class EditLock(val lockId: String, val expiresAt: String? = null)

object Origins {
    const val MACHINE = "MACHINE"
    const val USER = "USER"
}

/** The backend's recipe JSON ↔ the app's [Recipe]. */
object RecipeJson {
    private val known = Recipe.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

    fun decode(o: JsonObject): Recipe = ApiJson.decodeFromJsonElement(Recipe.serializer(), o)

    /** [edited] over [original]: every key the app models comes from [edited] (a cleared field disappears), the rest stays. */
    fun merge(original: JsonObject, edited: Recipe): JsonObject {
        val encoded = ApiJson.encodeToJsonElement(Recipe.serializer(), edited).jsonObject
        return JsonObject(original.filterKeys { it !in known } + encoded)
    }
}
