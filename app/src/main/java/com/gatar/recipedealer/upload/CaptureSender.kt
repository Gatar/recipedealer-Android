package com.gatar.recipedealer.upload

import com.gatar.recipedealer.model.Capture
import java.io.File

/** Outcome of one upload attempt, mapped from the example REST API (final-app.md section 8). */
sealed interface SendResult {
    /** 201 (created) or 200 (already there – retry after a lost response). */
    data class Accepted(val created: Boolean, val destination: String) : SendResult
    /** 400: schema validation failed; do not retry. */
    data class Invalid(val details: List<String>) : SendResult
    /** 401 / 403: stop the queue, ask for the token. */
    data class Unauthorized(val code: Int) : SendResult
    /** 413: keep failed, report. */
    data object TooLarge : SendResult
    /** 429, 5xx, network errors: retry with backoff, same captureId. */
    data class Retry(val reason: String) : SendResult
}

/** Sends a capture plus its attachment files (by attachment `name`). The status afterwards: `api.BackendClient`. */
interface CaptureSender {
    suspend fun send(capture: Capture, files: Map<String, File>): SendResult
}

/** No backend configured: the capture already lives in app storage; "sending" just marks it saved locally. */
class LocalCaptureSender : CaptureSender {
    override suspend fun send(capture: Capture, files: Map<String, File>): SendResult {
        val missing = capture.attachments.map { it.name }.filterNot { files[it]?.exists() == true }
        if (missing.isNotEmpty()) return SendResult.Invalid(missing.map { "missing attachment file: $it" })
        return SendResult.Accepted(created = true, destination = DESTINATION)
    }

    companion object {
        const val DESTINATION = "local"
    }
}
