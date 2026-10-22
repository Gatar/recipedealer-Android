package com.gatar.recipedealer.recipes

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.container
import com.gatar.recipedealer.upload.UploadQueue
import java.util.concurrent.TimeUnit

/** Recipe cache + inbox refresh in the background (periodic) or right now (after a decision / an edit). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.current.hasBackend) return Result.success()
        val r = c.recipeSync.sync()
        c.inbox.refresh()
        return when (r) {
            is ApiResult.Retry -> if (runAttemptCount < 3) Result.retry() else Result.success()
            is ApiResult.Unauthorized -> { UploadQueue.pauseForAuth(applicationContext); Result.failure() }
            else -> Result.success()
        }
    }

    companion object {
        private val ONLINE = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(ONLINE).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("recipe-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(ONLINE).build()
            WorkManager.getInstance(context).enqueueUniqueWork("recipe-sync-now", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
