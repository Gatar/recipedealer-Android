package com.gatar.recipedealer.recipes

import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.BackendClient
import com.gatar.recipedealer.api.failure
import com.gatar.recipedealer.inbox.Inbox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Incremental sync of [RecipeCache] from `GET /v1/recipes`: pages while `hasMore`, the cursor saved after every
 * page; no cursor (first sync, or a malformed one) → full sync that also drops recipes the feed no longer lists.
 * The tag catalog is refreshed first, so the recipes' tag ids are known. Runs on app start, pull-to-refresh, after
 * a decision or an edit, and periodically (SyncWorker).
 */
class RecipeSync(
    private val cache: RecipeCache,
    private val backend: () -> BackendClient?,
    private val pageSize: Int = PAGE,
    private val tags: TagCatalogStore? = null,
) {
    private val mutex = Mutex()

    /** Number of changed recipes. */
    suspend fun sync(): ApiResult<Int> = mutex.withLock {
        val client = backend() ?: return Inbox.NO_BACKEND
        tags?.refresh(client)?.let { if (it !is ApiResult.Ok) return it.failure() }
        var cursor = cache.cursor
        var full = cursor == null
        var reset = false
        val seen = mutableSetOf<String>()
        var changed = 0
        while (true) {
            val r = client.sync(cursor = cursor, limit = pageSize)
            if (r is ApiResult.Failed && r.code == 400 && r.error == ApiErrors.INVALID_REQUEST && cursor != null && !reset) {
                // malformed / outdated cursor: start over
                cache.clearCursor()
                cursor = null
                full = true
                reset = true
                continue
            }
            if (r !is ApiResult.Ok) return r.failure()
            val page = r.value
            page.items.filterNot { it.removed }.mapTo(seen) { it.recipeId }
            cursor = page.nextCursor ?: cursor
            changed += cache.apply(page.items, cursor)
            if (!page.hasMore || page.nextCursor == null) break
        }
        if (full) changed += cache.retainOnly(seen)
        ApiResult.Ok(changed)
    }

    companion object {
        const val PAGE = 200
    }
}
