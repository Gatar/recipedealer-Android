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
            _items.value = r.value.filter(::needsUser)
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

    /**
     * "Wyślij jako nowy": NEW for every candidate without a stored decision. Returns the last CaptureView (usually
     * `done`), or the first failure.
     */
    suspend fun resolveAllAsNew(view: CaptureView): ApiResult<CaptureView> {
        var last: ApiResult<CaptureView> = ApiResult.Ok(view)
        for (c in view.candidates.filter { it.resolution == null }) {
            last = resolve(view.captureId, c.index, Resolution.new())
            if (last !is ApiResult.Ok) return last
        }
        return last
    }

    /** "Ponownie odczytaj AI": the server runs the extraction again from the first LLM step; the capture is `processing`. */
    suspend fun forceLlm(captureId: String): ApiResult<CaptureView> {
        val r = (backend() ?: return NO_BACKEND).forceLlmExtraction(captureId)
        if (r is ApiResult.Ok) record(r.value)
        return r
    }

    /**
     * "Odrzuć": deletes the capture on the server (an invoice shared by mistake) and on this phone. Already deleted
     * (404 `not_found`, e.g. by another phone) counts as done.
     */
    suspend fun discard(captureId: String): ApiResult<Unit> {
        val r = (backend() ?: return NO_BACKEND).deleteCapture(captureId)
        val gone = r is ApiResult.Ok || (r is ApiResult.Failed && r.code == 404 && r.error == ApiErrors.NOT_FOUND)
        if (!gone) return r
        _items.update { list -> list.filterNot { it.captureId == captureId } }
        store.delete(captureId)
        return ApiResult.Ok(Unit)
    }

    fun record(view: CaptureView) {
        recordLocal(view)
        _items.update { list ->
            val rest = list.filterNot { it.captureId == view.captureId }
            if (needsUser(view)) (listOf(view) + rest).sortedByDescending { it.receivedAt.orEmpty() } else rest
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
        /** `needs_attention`: nothing could be extracted (no LLM step found a recipe, or none ran). */
        const val AWAITING_EXTRACTION = "AWAITING_EXTRACTION"
        /** `needs_attention`: the server's Claude API key is missing / invalid or has no credit. */
        const val LLM_UNAVAILABLE = "LLM_UNAVAILABLE"
        /** `needs_attention`: the Claude API refused this capture's request. */
        const val LLM_REQUEST_REJECTED = "LLM_REQUEST_REJECTED"
        const val PROCESSING_FAILED = "PROCESSING_FAILED"
        const val TARGET_GONE = "TARGET_GONE"

        /** A failed extraction: "Ponownie odczytaj AI" is the way out (not for TARGET_GONE, which is a new decision). */
        fun canReread(status: String?, reason: String?) =
            status == CaptureStates.NEEDS_ATTENTION && reason != TARGET_GONE

        fun isPending(status: String?) = status == CaptureStates.ACCEPTED || status == CaptureStates.PROCESSING

        /**
         * Something the user can do now: a decision, or help with a failure ("Ponownie odczytaj AI", "Odrzuć").
         * Also `AWAITING_EXTRACTION` since the server reads photos / PDFs itself: it does not come back to them.
         */
        fun needsUser(view: CaptureView) = view.status in WAITING

        /** "Odrzuć" (delete on the server) only for a capture that has not become a recipe. */
        fun canDiscard(status: String?, recipes: List<*>) = status in WAITING && recipes.isEmpty()
        private val RELOAD_ON = setOf(ApiErrors.RECIPE_GONE, ApiErrors.ALREADY_RESOLVED)
        val NO_BACKEND = ApiResult.Failed(0, "no_backend")
    }
}
