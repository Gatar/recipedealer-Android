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
import okhttp3.MultipartBody
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
    /** 401 / 403 without an endpoint-specific code: the device token is not valid → log in again. */
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
    const val TAG_EXISTS = "tag_exists"
    const val UNKNOWN_TAG = "unknown_tag"
    const val UNAUTHORIZED = "unauthorized"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val REGISTRATION_DISABLED = "registration_disabled"
    const val INVALID_REGISTRATION_CODE = "invalid_registration_code"
    const val USER_EXISTS = "user_exists"
    const val USER_LIMIT_REACHED = "user_limit_reached"
    const val TOO_MANY_REQUESTS = "too_many_requests"
    const val EXTRACTION_IN_PROGRESS = "extraction_in_progress"
    const val NO_LLM_INPUT = "no_llm_input"
    const val LLM_UNAVAILABLE = "llm_unavailable"
    const val CAPTURE_APPLIED = "capture_applied"
    const val CAPTURE_ID_TAKEN = "capture_id_taken"
    const val RECIPE_LIMIT_REACHED = "recipe_limit_reached"
    const val LLM_BUDGET_EXCEEDED = "llm_budget_exceeded"
    const val HOUSEHOLD_LIMIT_REACHED = "household_limit_reached"
    const val INVALID_INVITATION_CODE = "invalid_invitation_code"
    const val OWNER_REQUIRED = "owner_required"
    const val LAST_OWNER = "last_owner"
    const val DEFAULT_HOUSEHOLD = "default_household"
    const val EMAIL_EXISTS = "email_exists"
    const val EMAIL_REQUIRED = "email_required"
    const val MEMBER_EMAIL_REQUIRED = "member_email_required"
    const val INVALID_LOGIN_CODE = "invalid_login_code"
}

/**
 * Everything after the upload (which stays in [HttpCaptureSender]): capture status / inbox / decisions, recipe
 * candidates, the sync feed, recipe detail and revisions, search, blobs and editing with the edit lock; accounts and
 * households. Bearer token on every call but registration, login, a new household and joining one. [onUnauthorized] runs on every [ApiResult.Unauthorized].
 */
class BackendClient(
    baseUrl: String,
    private val token: String?,
    private val client: OkHttpClient = HttpCaptureSender.defaultClient,
    private val onUnauthorized: () -> Unit = {},
) {
    private val base = baseUrl.trim().trimEnd('/')

    // --- accounts ---

    suspend fun register(request: RegisterRequest): ApiResult<Session> =
        call(request(url("/v1/auth/register"), authorize = false).post(json(encode(RegisterRequest.serializer(), request))),
            Session.serializer())

    /** With a device name the user already has, replaces that device's token. */
    suspend fun login(request: LoginRequest): ApiResult<Session> =
        call(request(url("/v1/auth/login"), authorize = false).post(json(encode(LoginRequest.serializer(), request))),
            Session.serializer())

    /** Sets (enrolled user without one) or changes the token user's password; 403 `invalid_credentials` = wrong current. */
    suspend fun setPassword(change: PasswordChange): ApiResult<Unit> =
        execute(request(url("/v1/me/password")).put(json(encode(PasswordChange.serializer(), change)))) { }

    // --- households ---

    /** A new household; the caller becomes its owner. 409 `user_exists` / `household_limit_reached`, 429. */
    suspend fun createHousehold(request: NewHouseholdRequest): ApiResult<Session> =
        call(request(url("/v1/auth/households"), authorize = false).post(json(encode(NewHouseholdRequest.serializer(), request))),
            Session.serializer())

    /** Joins with an invitation code. 403 `invalid_invitation_code`, 409 `user_exists` / `user_limit_reached`, 429. */
    suspend fun join(request: JoinRequest): ApiResult<Session> =
        call(request(url("/v1/auth/join"), authorize = false).post(json(encode(JoinRequest.serializer(), request))),
            Session.serializer())

    /** A new device for an existing account. 403 `invalid_login_code`, 429. */
    suspend fun loginWithCode(request: CodeLoginRequest): ApiResult<Session> =
        call(request(url("/v1/auth/login-code"), authorize = false).post(json(encode(CodeLoginRequest.serializer(), request))),
            Session.serializer())

    suspend fun me(): ApiResult<Me> = call(get(url("/v1/me")), Me.serializer())

    /** Sets ("" removes) the caller's e-mail address. 400 wrong shape, 409 `email_exists` / `email_required`. */
    suspend fun setEmail(email: String): ApiResult<Me> =
        call(request(url("/v1/me")).patch(json(encode(EmailChange.serializer(), EmailChange(email)))), Me.serializer())

    /** Deletes the caller's account (the last user takes the household along). 409 `last_owner`. */
    suspend fun deleteMe(): ApiResult<Unit> = execute(request(url("/v1/me")).delete()) { }

    suspend fun renameHousehold(name: String): ApiResult<HouseholdView> =
        call(request(url("/v1/household")).patch(json(encode(HouseholdRename.serializer(), HouseholdRename(name)))),
            HouseholdView.serializer())

    /** With all its users (owner). 409 `default_household`. */
    suspend fun deleteHousehold(): ApiResult<Unit> = execute(request(url("/v1/household")).delete()) { }

    suspend fun members() = call(get(url("/v1/household/members")), ListSerializer(Member.serializer()))

    /** Answers with all the members. 409 `last_owner` when demoting the only owner. */
    suspend fun setRole(userId: String, role: String) =
        call(request(url("/v1/household/members/$userId")).patch(json(encode(RoleChange.serializer(), RoleChange(role)))),
            ListSerializer(Member.serializer()))

    suspend fun removeMember(userId: String): ApiResult<Unit> =
        execute(request(url("/v1/household/members/$userId")).delete()) { }

    /** A single-use login code for a user of the household (owner), 48 h; replaces that user's earlier code. */
    suspend fun createLoginCode(userId: String): ApiResult<LoginCode> =
        call(request(url("/v1/household/members/$userId/login-code")).post(EMPTY), LoginCode.serializer())

    /** Owners any device of the household, members only their own; 204 also when already revoked. */
    suspend fun revokeDevice(deviceId: String): ApiResult<Unit> =
        execute(request(url("/v1/household/devices/$deviceId")).delete()) { }

    /** A single-use code for 48 h; [Invitation.code] is only in this answer. */
    suspend fun createInvitation(): ApiResult<Invitation> =
        call(request(url("/v1/household/invitations")).post(EMPTY), Invitation.serializer())

    suspend fun invitations() = call(get(url("/v1/household/invitations")), ListSerializer(Invitation.serializer()))

    suspend fun revokeInvitation(invitationId: String): ApiResult<Unit> =
        execute(request(url("/v1/household/invitations/$invitationId")).delete()) { }

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

    /**
     * Forces the extraction cascade again from the first LLM step; 202 with the capture now `processing`.
     * 409 `extraction_in_progress` / `no_llm_input`, 503 `llm_unavailable`.
     */
    suspend fun forceLlmExtraction(captureId: String): ApiResult<CaptureView> =
        call(request(url("/v1/captures/$captureId/llm-extraction")).post(EMPTY), CaptureView.serializer())

    /**
     * Deletes a capture that never became a recipe, with its attachments; 204. 404 `not_found` also when already
     * deleted; 409 `capture_applied` / `extraction_in_progress`.
     */
    suspend fun deleteCapture(captureId: String): ApiResult<Unit> =
        execute(request(url("/v1/captures/$captureId")).delete()) { }

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

    /** "Statystyki dla nerdów"; 404 also for a deleted / merged recipe. */
    suspend fun extractionStats(recipeId: String) =
        call(get(url("/v1/recipes/$recipeId/extraction-stats")), ExtractionStats.serializer())

    suspend fun search(q: String) =
        call(get(url("/v1/recipes/search") { addQueryParameter("q", q) }), ListSerializer(SearchHit.serializer()))

    /** Blob bytes (`thumbnailBlob`); immutable, the caller caches them. */
    suspend fun blob(sha256: String): ApiResult<ByteArray> =
        execute(request(url("/v1/blobs/$sha256")).header("Accept", "*/*").get()) { r -> r.body!!.bytes() }

    /**
     * A recipe photo from the phone (`POST /v1/blobs`, already scaled down by [com.gatar.recipedealer.recipes.RecipePhoto]);
     * the server stores it as JPEG of at most 1600 px. [UploadedPhoto.ref] goes into `images[0]` with the next save.
     */
    suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<UploadedPhoto> =
        call(request(url("/v1/blobs")).post(MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "photo.jpg", jpeg.toRequestBody(JPEG)).build()), UploadedPhoto.serializer())

    // --- tags ---

    suspend fun tags() = call(get(url("/v1/tags")), TagCatalog.serializer())

    suspend fun createCategory(change: CategoryChange) =
        call(request(url("/v1/tags/categories")).post(json(encode(CategoryChange.serializer(), change))), TagCatalog.serializer())

    suspend fun updateCategory(category: String, change: CategoryChange) =
        call(request(url("/v1/tags/categories/$category")).patch(json(encode(CategoryChange.serializer(), change))),
            TagCatalog.serializer())

    /** Also deletes its tags and their assignments. */
    suspend fun deleteCategory(category: String) =
        call(request(url("/v1/tags/categories/$category")).delete(), TagCatalog.serializer())

    suspend fun createTag(category: String, change: TagChange) =
        call(request(url("/v1/tags/categories/$category/tags")).post(json(encode(TagChange.serializer(), change))),
            TagCatalog.serializer())

    suspend fun updateTag(category: String, tag: String, change: TagChange) =
        call(request(url("/v1/tags/categories/$category/tags/$tag")).patch(json(encode(TagChange.serializer(), change))),
            TagCatalog.serializer())

    suspend fun deleteTag(category: String, tag: String) =
        call(request(url("/v1/tags/categories/$category/tags/$tag")).delete(), TagCatalog.serializer())

    /** Replaces the recipe's tags (no edit lock); the answer lists them in catalog order. */
    suspend fun setRecipeTags(recipeId: String, tags: List<String>) =
        call(request(url("/v1/recipes/$recipeId/tags")).put(json(encode(RecipeTags.serializer(), RecipeTags(tags)))),
            RecipeTags.serializer())

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

    private fun request(url: HttpUrl, authorize: Boolean = true) = Request.Builder().url(url).apply {
        if (authorize && !token.isNullOrBlank()) header("Authorization", "Bearer $token")
        header("Accept", "application/json")
    }

    private fun get(url: HttpUrl) = request(url).get()

    private fun <T> encode(serializer: KSerializer<T>, value: T) = ApiJson.encodeToString(serializer, value)

    private suspend fun <T> call(b: Request.Builder, serializer: KSerializer<T>): ApiResult<T> =
        execute(b) { r -> ApiJson.decodeFromString(serializer, r.body!!.string()) }

    private suspend fun <T> execute(b: Request.Builder, parse: (Response) -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(b.build()).execute().use { r ->
                when (r.code) {
                    in 200..299 -> ApiResult.Ok(parse(r), r.code, r.header("ETag"))
                    304 -> ApiResult.NotModified
                    in 500..599 -> serverFailure(r.code, r.body?.string().orEmpty())
                    else -> failure(r.code, r.body?.string().orEmpty())
                }
            }.also { if (it is ApiResult.Unauthorized) onUnauthorized() }
        } catch (e: IOException) {
            ApiResult.Retry("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: SerializationException) {
            ApiResult.Failed(0, "invalid_response", listOfNotNull(e.message))
        } catch (e: IllegalArgumentException) {
            ApiResult.Failed(0, "invalid_url", listOfNotNull(e.message))
        }
    }

    /**
     * `{"error": "<code>", "details": [...]}`, or the 409 lock shape `{"error": "locked", "lockedBy", "expiresAt"}`.
     * 401 / 403 with an endpoint-specific code (`invalid_credentials`, `registration_disabled`, …) and 429 with a
     * code (login limits) are answers for the user, not a dead token or a reason to retry.
     */
    private fun failure(code: Int, body: String): ApiResult<Nothing> {
        val o = runCatching { ApiJson.parseToJsonElement(body).jsonObject }.getOrNull()
        fun str(key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull
        val error = str("error")
        if ((code == 401 || code == 403) && (error == null || error == ApiErrors.UNAUTHORIZED)) return ApiResult.Unauthorized(code)
        if (code == 429 && error == null) return ApiResult.Retry("HTTP 429")
        if (code == 409 && error == "locked") return ApiResult.Locked(str("lockedBy"), str("expiresAt"))
        val details = (o?.get("details") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return ApiResult.Failed(code, error, details)
    }

    /** 5xx is retryable, except 503 `llm_unavailable`: no API key on the server, retrying does not help. */
    private fun serverFailure(code: Int, body: String): ApiResult<Nothing> {
        val f = failure(code, body)
        return if (f is ApiResult.Failed && f.error == ApiErrors.LLM_UNAVAILABLE) f else ApiResult.Retry("HTTP $code")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JPEG = "image/jpeg".toMediaType()
        val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
        fun json(s: String) = s.toRequestBody(JSON)
    }
}
