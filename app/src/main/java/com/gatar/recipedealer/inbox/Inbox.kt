package com.gatar.recipedealer.inbox

import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.CaptureStates
import com.gatar.recipedealer.api.CaptureView
import com.gatar.recipedealer.api.Resolution
import com.gatar.recipedealer.store.CaptureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Captures waiting for the user (`needs_decision`, `needs_attention`) – from this phone and from the other phones of
 * the household – and the NEW / UPDATE decisions. Every CaptureView seen also refreshes the local capture state.
 * In memory only: the local captures keep their own copy in `state.json`.
 */
class Inbox(private val store: CaptureStore, private val backend: () -> BackendClient?) {
    private val _items = MutableStateFlow<List<CaptureView>>(emptyList())
    val items: StateFlow<List<CaptureView>> = _items.asStateFlow()

    suspend fun refresh(): ApiResult<List<CaptureView>> {
        val client = backend() ?: return NO_BACKEND
        val r = client.captures()
        if (r is ApiResult.Ok) {
            _items.value = r.value
            r.value.forEach(::recordLocal)
        }
        return r
    }

    suspend fun load(captureId: String): ApiResult<CaptureView> {
        val r = (backend() ?: return NO_BACKEND).capture(captureId)
        if (r is ApiResult.Ok) record(r.value)
        return r
    }

    /**
     * Stores the decision for one candidate. On `recipe_gone` / `already_resolved` the capture is reloaded, so the
     * screen shows fresh candidates; the error is still returned for the message.
     */
    suspend fun resolve(captureId: String, index: Int, resolution: Resolution): ApiResult<CaptureView> {
        val client = backend() ?: return NO_BACKEND
        val r = client.resolve(captureId, index, resolution)
        when {
            r is ApiResult.Ok -> record(r.value)
            r is ApiResult.Failed && r.error in RELOAD_ON -> load(captureId)
        }
        return r
    }

    fun record(view: CaptureView) {
        recordLocal(view)
        _items.update { list ->
            val rest = list.filterNot { it.captureId == view.captureId }
            if (view.status in WAITING) (listOf(view) + rest).sortedByDescending { it.receivedAt.orEmpty() } else rest
        }
    }

    /** Captures of other phones are not in the local store: nothing to update. */
    private fun recordLocal(view: CaptureView) {
        val old = store.get(view.captureId)?.state ?: return
        val new = old.copy(
            backendStatus = view.status,
            backendReason = view.reason,
            backendMessage = view.message,
            backendRecipes = view.recipes,
            backendTitles = view.recipes.mapNotNull { it.title },
            candidates = view.candidates,
        )
        if (new != old) store.updateState(view.captureId) { new }
    }

    companion object {
        val WAITING = setOf(CaptureStates.NEEDS_DECISION, CaptureStates.NEEDS_ATTENTION)
        private val RELOAD_ON = setOf(ApiErrors.RECIPE_GONE, ApiErrors.ALREADY_RESOLVED)
        val NO_BACKEND = ApiResult.Failed(0, "no_backend")
    }
}
