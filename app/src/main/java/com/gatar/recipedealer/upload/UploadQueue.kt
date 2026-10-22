package com.gatar.recipedealer.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gatar.recipedealer.Notifications
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.api.CaptureStates
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.settings.AppSettings
import com.gatar.recipedealer.store.CaptureStatus
import java.util.concurrent.TimeUnit

/**
 * Offline queue: one unique WorkManager job per capture (same captureId on every retry → idempotent PUT),
 * network constraint, exponential backoff for 429 / 5xx / network errors.
 */
object UploadQueue {
    private const val TAG = "upload"
    private const val KEY_ID = "captureId"
    const val MAX_ATTEMPTS = 12

    fun senderFor(s: AppSettings): CaptureSender =
        if (s.hasBackend) HttpCaptureSender(s.baseUrl, s.token) else LocalCaptureSender()

    fun enqueue(context: Context, id: String) {
        context.container.store.updateState(id) { it.copy(status = CaptureStatus.QUEUED, error = null, needsAuth = false) }
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-$id", ExistingWorkPolicy.KEEP, request)
    }

    /** Queued captures whose work may be gone (app start, a changed backend) and, after a token change, those blocked by auth. */
    fun resume(context: Context, includeAuthFailures: Boolean) {
        context.container.store.entries.value
            .filter { it.capture != null && (it.state.status == CaptureStatus.QUEUED || (includeAuthFailures && it.state.needsAuth)) }
            .forEach { enqueue(context, it.id) }
    }

    /** 401 / 403: stop the queue until the token is changed. */
    fun pauseForAuth(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        Notifications.authNeeded(context)
    }

    internal fun id(data: androidx.work.Data) = data.getString(KEY_ID)

    fun scheduleStatusNow(context: Context, id: String) = scheduleStatus(context, id, delaySeconds = 0)

    fun scheduleStatus(context: Context, id: String, delaySeconds: Long = 30) {
        val request = OneTimeWorkRequestBuilder<StatusWorker>()
            .setInputData(workDataOf(KEY_ID to id))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("status-$id", ExistingWorkPolicy.REPLACE, request)
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = UploadQueue.id(inputData) ?: return Result.failure()
        val c = applicationContext.container
        val entry = c.store.get(id) ?: return Result.failure()
        if (entry.state.status == CaptureStatus.SENT) return Result.success()
        return when (val r = CaptureUploadRunner(c.store).upload(id, UploadQueue.senderFor(c.settings.current))) {
            is SendResult.Accepted -> {
                if (r.destination != LocalCaptureSender.DESTINATION) UploadQueue.scheduleStatus(applicationContext, id)
                Result.success()
            }
            is SendResult.Retry -> if (runAttemptCount + 1 >= UploadQueue.MAX_ATTEMPTS) {
                c.store.updateState(id) {
                    it.copy(status = CaptureStatus.FAILED, error = "Nie udało się wysłać po ${UploadQueue.MAX_ATTEMPTS} próbach: ${r.reason}")
                }
                Result.failure()
            } else Result.retry()
            is SendResult.Unauthorized -> {
                UploadQueue.pauseForAuth(applicationContext)
                Result.failure()
            }
            is SendResult.Invalid, SendResult.TooLarge -> Result.failure()
        }
    }
}

/**
 * `GET /v1/captures/{id}` after the upload until processing ends; then a notification when the user has to decide
 * (`needs_decision`) or help (`needs_attention`), so the capture does not wait silently.
 */
class StatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = UploadQueue.id(inputData) ?: return Result.failure()
        val c = applicationContext.container
        val before = c.store.get(id)?.state?.backendStatus
        val view = when (val r = c.inbox.load(id)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Retry -> return if (runAttemptCount < 8) Result.retry() else Result.success()
            is ApiResult.Unauthorized -> { UploadQueue.pauseForAuth(applicationContext); return Result.failure() }
            else -> return Result.success()
        }
        val pending = view.status == CaptureStates.ACCEPTED || view.status == CaptureStates.PROCESSING
        if (pending) return if (runAttemptCount < 8) Result.retry() else Result.success()
        if (view.status != before && view.status in Inbox.WAITING) {
            c.store.get(id)?.let { Notifications.decisionNeeded(applicationContext, it.id, it.title ?: view.title, view) }
        }
        return Result.success()
    }
}
