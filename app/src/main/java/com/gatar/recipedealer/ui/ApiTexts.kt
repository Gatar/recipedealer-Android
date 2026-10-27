package com.gatar.recipedealer.ui

import android.content.Context
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiErrors
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.CaptureStates
import com.gatar.recipedealer.inbox.Inbox

/**
 * A 404 without the backend's own `not_found` code: the route itself is missing (a backend from before the
 * feature), not the object it names.
 */
fun isMissingRoute(r: ApiResult<*>) = r is ApiResult.Failed && r.code == 404 && r.error != ApiErrors.NOT_FOUND

/** Polish text for a failed backend call; null for success. */
fun apiErrorText(context: Context, r: ApiResult<*>): String? = when (r) {
    is ApiResult.Ok, ApiResult.NotModified -> null
    is ApiResult.Unauthorized -> context.getString(R.string.health_unauthorized)
    is ApiResult.Retry -> context.getString(R.string.api_offline, r.reason)
    is ApiResult.Locked -> context.getString(R.string.edit_locked_by, r.lockedBy ?: "?")
    is ApiResult.Failed -> accountErrorText(context, r) ?: when (r.error) {
        Inbox.NO_BACKEND.error -> context.getString(R.string.api_no_backend)
        ApiErrors.RECIPE_GONE -> context.getString(R.string.api_recipe_gone)
        ApiErrors.ALREADY_RESOLVED -> context.getString(R.string.api_already_resolved)
        ApiErrors.NOT_FOUND -> context.getString(R.string.api_not_found)
        else -> null
    }.let { text -> listOfNotNull(text ?: "HTTP ${r.code}: ${r.error ?: "?"}", r.details.joinToString("\n").ifEmpty { null }).joinToString("\n") }
}

/** Login, registration, password: the Polish text says it all, without the server's English details. */
private fun accountErrorText(context: Context, r: ApiResult.Failed): String? = when (r.error) {
    ApiErrors.INVALID_CREDENTIALS -> context.getString(
        if (r.code == 403) R.string.auth_wrong_current_password else R.string.auth_invalid_credentials)
    ApiErrors.REGISTRATION_DISABLED -> context.getString(R.string.auth_registration_disabled)
    ApiErrors.INVALID_REGISTRATION_CODE -> context.getString(R.string.auth_invalid_registration_code)
    ApiErrors.USER_EXISTS -> context.getString(R.string.auth_user_exists)
    ApiErrors.USER_LIMIT_REACHED -> context.getString(R.string.auth_user_limit_reached)
    ApiErrors.TOO_MANY_REQUESTS -> context.getString(R.string.auth_too_many_requests)
    else -> null
}

/** `CaptureView.status` for the user. */
fun backendStatusLabel(context: Context, status: String): String = when (status) {
    CaptureStates.ACCEPTED -> context.getString(R.string.backend_accepted)
    CaptureStates.PROCESSING -> context.getString(R.string.backend_processing)
    CaptureStates.NEEDS_DECISION -> context.getString(R.string.backend_needs_decision)
    CaptureStates.NEEDS_ATTENTION -> context.getString(R.string.backend_needs_attention)
    CaptureStates.DONE -> context.getString(R.string.backend_done)
    else -> status
}

/** `CaptureView.reason`; the server's `message` is shown next to it. */
fun backendReasonText(context: Context, reason: String?): String? = when (reason) {
    "PROCESSING_FAILED" -> context.getString(R.string.reason_processing_failed)
    Inbox.AWAITING_EXTRACTION -> context.getString(R.string.reason_awaiting_extraction)
    "TARGET_GONE" -> context.getString(R.string.reason_target_gone)
    else -> reason
}
