package com.gatar.recipedealer.api

import com.gatar.recipedealer.upload.HttpCaptureSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** Outcome of one backend call. */
sealed interface ApiResult<out T> {
    /** 2xx; [etag] as received (quoted), for `If-Match` / `If-None-Match`. */
    data class Ok<T>(val value: T, val code: Int = 200, val etag: String? = null) : ApiResult<T>
    /** 304 for a conditional GET. */
    data object NotModified : ApiResult<Nothing>
    /** 401 / 403: the existing "needs auth" handling. */
    data class Unauthorized(val code: Int) : ApiResult<Nothing>
    /** 409 `locked`: someone else edits the recipe. */
    data class Locked(val lockedBy: String?, val expiresAt: String?) : ApiResult<Nothing>
    /** Other 4xx: `{"error": "<code>", "details": [...]}`; do not retry. */
    data class Failed(val code: Int, val error: String?, val details: List<String> = emptyList()) : ApiResult<Nothing>
    /** 429, 5xx, network errors: retryable. */
    data class Retry(val reason: String) : ApiResult<Nothing>
}

fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Ok)?.value

/**
 * The recipe ETag is `"<headRevisionId>"`. Built from the body, not taken from the `ETag` header: a compressing
 * proxy rewrites that header (Caddy's `encode` sends `"<id>-gzip"`), and the backend would answer 412 to it.
 */
fun etagOf(headRevisionId: String) = "\"$headRevisionId\""

/** A non-Ok result passed on as another result type (every variant but Ok is `ApiResult<Nothing>`). */
@Suppress("UNCHECKED_CAST")
fun <T> ApiResult<*>.failure(): ApiResult<T> {
    check(this !is ApiResult.Ok) { "not a failure: $this" }
    return this as ApiResult<T>
}

/** Error codes of `ApiResult.Failed.error`. */
object ApiErrors {
    const val NOT_FOUND = "not_found"
    const val INVALID_REQUEST = "invalid_request"
    const val INVALID_RECIPE = "invalid_recipe"
    const val RECIPE_GONE = "recipe_gone"
    const val ALREADY_RESOLVED = "already_resolved"
    const val RECIPE_DELETED = "recipe_deleted"
    const val RECIPE_MERGED = "recipe_merged"
    const val LOCK_REQUIRED = "lock_required"
    const val LOCK_EXPIRED = "lock_expired"
    const val HEAD_CHANGED = "head_changed"
    const val IF_MATCH_REQUIRED = "if_match_required"
}

/**
 * Everything after the upload (which stays in [HttpCaptureSender]): capture status / inbox / decisions, recipe
 * candidates, the sync feed, recipe detail and revisions, search, blobs and editing with the edit lock.
 * Bearer token on every call (D3).
 */
class BackendClient(
    baseUrl: String,
    private val token: String?,
    private val client: OkHttpClient = HttpCaptureSender.defaultClient,
) {
    private val base = baseUrl.trim().trimEnd('/')

    // --- captures ---

    suspend fun capture(captureId: String): ApiResult<CaptureView> =
        call(get(url("/v1/captures/$captureId")), CaptureView.serializer())

    /** The inbox: captures in the given states (server default: needs_decision, needs_attention), newest first. */
    suspend fun captures(statuses: List<String> = listOf(CaptureStates.NEEDS_DECISION, CaptureStates.NEEDS_ATTENTION)) =
        call(get(url("/v1/captures") { addQueryParameter("status", statuses.joinToString(",")) }),
            ListSerializer(CaptureView.serializer()))

    /** NEW or UPDATE for one extracted candidate; idempotent. */
    suspend fun resolve(captureId: String, index: Int, resolution: Resolution): ApiResult<CaptureView> =
        call(request(url("/v1/captures/$captureId/extracted/$index/resolution"))
            .put(json(ApiJson.encodeToString(Resolution.serializer(), resolution))), CaptureView.serializer())

    // --- recipes ---

    suspend fun recipeCandidates(title: String?, url: String?) =
        call(get(url("/v1/recipes/candidates") {
            title?.takeIf { it.isNotBlank() }?.let { addQueryParameter("title", it) }
            url?.takeIf { it.isNotBlank() }?.let { addQueryParameter("url", it) }
        }), ListSerializer(RecipeMatch.serializer()))

    /** One page of the sync feed; pass the saved `nextCursor` (preferred) or `updatedSince`. */
    suspend fun sync(cursor: String? = null, updatedSince: String? = null, limit: Int? = null) =
        call(get(url("/v1/recipes") {
            updatedSince?.let { addQueryParameter("updatedSince", it) }
            cursor?.let { addQueryParameter("cursor", it) }
            limit?.let { addQueryParameter("limit", it.toString()) }
        }), SyncPage.serializer())

    /** [etag]: the last ETag seen → 304 [ApiResult.NotModified] when the head did not change. */
    suspend fun recipe(recipeId: String, etag: String? = null): ApiResult<RecipeView> =
        call(get(url("/v1/recipes/$recipeId")).apply { etag?.let { header("If-None-Match", it) } }, RecipeView.serializer())

    suspend fun revisions(recipeId: String) =
        call(get(url("/v1/recipes/$recipeId/revisions")), ListSerializer(RevisionView.serializer()))

    suspend fun search(q: String) =
        call(get(url("/v1/recipes/search") { addQueryParameter("q", q) }), ListSerializer(SearchHit.serializer()))

    /** Blob bytes (`thumbnailBlob`); immutable, the caller caches them. */
    suspend fun blob(sha256: String): ApiResult<ByteArray> =
        execute(request(url("/v1/blobs/$sha256")).header("Accept", "*/*").get()) { r -> r.body!!.bytes() }

    // --- editing ---

    suspend fun lock(recipeId: String): ApiResult<EditLock> =
        call(request(url("/v1/recipes/$recipeId/lock")).post(EMPTY), EditLock.serializer())

    /** Heartbeat: extends the 5 min lease. */
    suspend fun renewLock(recipeId: String, lockId: String): ApiResult<EditLock> =
        call(request(url("/v1/recipes/$recipeId/lock/$lockId")).put(EMPTY), EditLock.serializer())

    /** Idempotent. */
    suspend fun releaseLock(recipeId: String, lockId: String): ApiResult<Unit> =
        execute(request(url("/v1/recipes/$recipeId/lock/$lockId")).delete()) { }

    /** 201 new USER revision (new head) / 200 identical to the head; the server releases the lock either way. */
    suspend fun saveRevision(recipeId: String, lockId: String, ifMatch: String, recipe: JsonObject): ApiResult<RecipeView> =
        call(request(url("/v1/recipes/$recipeId/revisions"))
            .header("Lock-Id", lockId)
            .header("If-Match", ifMatch)
            .post(json(ApiJson.encodeToString(JsonObject.serializer(), recipe))), RecipeView.serializer())

    /** Soft delete inside an edit session. */
    suspend fun deleteRecipe(recipeId: String, lockId: String, ifMatch: String? = null): ApiResult<Unit> =
        execute(request(url("/v1/recipes/$recipeId")).header("Lock-Id", lockId)
            .apply { ifMatch?.let { header("If-Match", it) } }.delete()) { }

    // --- plumbing ---

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        (base + path).toHttpUrl().newBuilder().apply(query).build()

    private fun request(url: HttpUrl) = Request.Builder().url(url).apply {
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        header("Accept", "application/json")
    }

    private fun get(url: HttpUrl) = request(url).get()

    private suspend fun <T> call(b: Request.Builder, serializer: KSerializer<T>): ApiResult<T> =
        execute(b) { r -> ApiJson.decodeFromString(serializer, r.body!!.string()) }

    private suspend fun <T> execute(b: Request.Builder, parse: (Response) -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(b.build()).execute().use { r ->
                when (r.code) {
                    in 200..299 -> ApiResult.Ok(parse(r), r.code, r.header("ETag"))
                    304 -> ApiResult.NotModified
                    401, 403 -> ApiResult.Unauthorized(r.code)
                    429 -> ApiResult.Retry("HTTP 429")
                    in 500..599 -> ApiResult.Retry("HTTP ${r.code}")
                    else -> failure(r.code, r.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            ApiResult.Retry("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: SerializationException) {
            ApiResult.Failed(0, "invalid_response", listOfNotNull(e.message))
        } catch (e: IllegalArgumentException) {
            ApiResult.Failed(0, "invalid_url", listOfNotNull(e.message))
        }
    }

    /** `{"error": "<code>", "details": [...]}`, or the 409 lock shape `{"error": "locked", "lockedBy", "expiresAt"}`. */
    private fun failure(code: Int, body: String): ApiResult<Nothing> {
        val o = runCatching { ApiJson.parseToJsonElement(body).jsonObject }.getOrNull()
        fun str(key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull
        val error = str("error")
        if (code == 409 && error == "locked") return ApiResult.Locked(str("lockedBy"), str("expiresAt"))
        val details = (o?.get("details") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return ApiResult.Failed(code, error, details)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
        fun json(s: String) = s.toRequestBody(JSON)
    }
}
