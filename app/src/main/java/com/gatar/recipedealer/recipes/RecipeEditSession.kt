package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.RecipeJson
import com.gatar.recipedealer.api.RecipeView
import com.gatar.recipedealer.api.etagOf
import com.gatar.recipedealer.model.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * One online edit of a recipe (no offline editing): GET the head (its ETag goes to `If-Match`), take the edit lock,
 * renew it every 2 minutes (5 minute lease), then save a USER revision, delete the recipe, or release the lock.
 * A lost lock or a changed head never drops the user's text: the editor keeps it, only saving fails.
 */
class RecipeEditSession(
    private val client: BackendClient,
    private val cache: RecipeCache,
    val recipeId: String,
    private val scope: CoroutineScope,
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
) {
    enum class Phase { STARTING, LOCKED_BY_OTHER, EDITING, FINISHED, FAILED }

    data class State(
        val phase: Phase = Phase.STARTING,
        /** Content the editor starts from (the head, or a revision being applied). */
        val initial: Recipe? = null,
        val lockId: String? = null,
        /** ETag of the head the edit is based on (`If-Match`). */
        val etag: String? = null,
        val lockedBy: String? = null,
        /** The lease expired or someone else holds the lock now: saving will fail. */
        val lockLost: Boolean = false,
        /** 412: the recipe changed since the edit started. */
        val headChanged: Boolean = false,
        val deleted: Boolean = false,
        /** The last call that went wrong (shown by the UI). */
        val error: ApiResult<*>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The JSON the edit is written over (keys the app does not model survive). */
    private var original: JsonObject? = null
    private var heartbeat: Job? = null

    /** [revision]: content of an older revision / machine proposal to start from instead of the head. */
    suspend fun start(revision: JsonObject? = null): ApiResult<*> {
        stopHeartbeat()
        _state.value = State()
        val head = client.recipe(recipeId)
        if (head !is ApiResult.Ok) return head.also { fail(it, gone = it.isGone()) }
        cache.putDetail(head.value)
        val etag = etagOf(head.value.headRevisionId)
        return when (val lock = client.lock(recipeId)) {
            is ApiResult.Ok -> {
                original = revision ?: head.value.recipe
                _state.value = State(Phase.EDITING, RecipeJson.decode(original!!), lock.value.lockId, etag)
                startHeartbeat(lock.value.lockId)
                lock
            }
            is ApiResult.Locked -> {
                _state.value = State(Phase.LOCKED_BY_OTHER, lockedBy = lock.lockedBy, error = lock)
                lock
            }
            else -> lock.also { fail(it, gone = it.isGone()) }
        }
    }

    /** 201 (new USER revision) or 200 (nothing changed): the server released the lock. */
    suspend fun save(edited: Recipe): ApiResult<RecipeView> {
        val s = _state.value
        val lockId = s.lockId ?: return ApiResult.Failed(0, ApiErrors.LOCK_REQUIRED)
        val r = client.saveRevision(recipeId, lockId, s.etag!!, RecipeJson.merge(original!!, edited))
        when {
            r is ApiResult.Ok -> {
                stopHeartbeat()
                cache.putDetail(r.value)
                _state.update { it.copy(phase = Phase.FINISHED, lockId = null, error = null) }
            }
            r is ApiResult.Failed && r.code == 412 -> _state.update { it.copy(headChanged = true, error = r) }
            r.isLockLost() -> lostLock(r)
            r.isGone() -> fail(r, gone = true)
            else -> _state.update { it.copy(error = r) }
        }
        return r
    }

    /** Soft delete inside the edit session (after the user confirmed). */
    suspend fun delete(): ApiResult<Unit> {
        val s = _state.value
        val lockId = s.lockId ?: return ApiResult.Failed(0, ApiErrors.LOCK_REQUIRED)
        val r = client.deleteRecipe(recipeId, lockId, s.etag)
        when {
            r is ApiResult.Ok -> {
                stopHeartbeat()
                cache.remove(recipeId)
                _state.update { it.copy(phase = Phase.FINISHED, lockId = null, deleted = true, error = null) }
            }
            r is ApiResult.Failed && r.code == 412 -> _state.update { it.copy(headChanged = true, error = r) }
            r.isLockLost() -> lostLock(r)
            r.isGone() -> fail(r, gone = true)
            else -> _state.update { it.copy(error = r) }
        }
        return r
    }

    /** Editor closed without saving: release the lock (idempotent; a killed app just lets the lease expire). */
    suspend fun cancel() {
        stopHeartbeat()
        val lockId = _state.value.lockId ?: return
        _state.update { it.copy(phase = Phase.FINISHED, lockId = null) }
        client.releaseLock(recipeId, lockId)
    }

    private fun startHeartbeat(lockId: String) {
        heartbeat = scope.launch {
            while (isActive) {
                delay(heartbeatMillis)
                val r = client.renewLock(recipeId, lockId)
                // network trouble: try again on the next beat, the lease still has 3 minutes
                if (r.isLockLost() || r.isGone()) { lostLock(r); break }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    private fun lostLock(r: ApiResult<*>) {
        stopHeartbeat()
        _state.update { it.copy(lockLost = true, error = r) }
    }

    private fun fail(r: ApiResult<*>, gone: Boolean) {
        stopHeartbeat()
        if (gone) cache.remove(recipeId)
        _state.update { it.copy(phase = Phase.FAILED, lockId = null, error = r) }
    }

    private fun ApiResult<*>.isLockLost() = this is ApiResult.Locked ||
        (this is ApiResult.Failed && code == 409 && error in setOf(ApiErrors.LOCK_EXPIRED, ApiErrors.LOCK_REQUIRED))

    private fun ApiResult<*>.isGone() = this is ApiResult.Failed && code == 410

    companion object {
        const val HEARTBEAT_MILLIS = 2 * 60 * 1000L
    }
}
