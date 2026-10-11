package com.gatar.recipedealer.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gatar.recipedealer.Notifications
import com.gatar.recipedealer.container
import com.gatar.recipedealer.share.Route
import com.gatar.recipedealer.share.ShareRequest
import com.gatar.recipedealer.store.CaptureStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `shortService` foreground service that runs the captures started by shares (several at once are fine). Stops
 * when the last one is done. Its WebViews are off-screen (no window), created on the main thread.
 */
class CaptureService : LifecycleService() {
    private val running = mutableMapOf<String, Job>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val id = intent?.getStringExtra(EXTRA_ID)
        val processor = container.processor
        val job = id?.let(processor::loadJob)
        if (job == null || running.containsKey(job.id)) {
            if (running.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(Notifications.PROGRESS_ID, Notifications.progress(this, running.size + 1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        running[job.id] = lifecycleScope.launch {
            try {
                val entry = processor.run(job)
                ResultNotifier.notify(this@CaptureService, entry)
                if (container.settings.current.autoSend) container.send(entry.id)
            } finally {
                running.remove(job.id)
                if (running.isEmpty()) stopSelf() else updateProgress()
            }
        }
        return START_NOT_STICKY
    }

    private fun updateProgress() {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifications.PROGRESS_ID, Notifications.progress(this, running.size))
    }

    /** The short-service limit (~3 min) was reached: give up on what is still running. */
    override fun onTimeout(startId: Int) = giveUp()

    override fun onTimeout(startId: Int, fgsType: Int) = giveUp()

    private fun giveUp() {
        running.keys.toList().forEach { id ->
            container.store.updateState(id) { it.copy(status = CaptureStatus.FAILED, error = "Przekroczony czas przetwarzania") }
        }
        running.values.forEach { it.cancel() }
        stopSelf()
    }

    companion object {
        private const val EXTRA_ID = "captureId"

        /** Saves the job and starts processing it. Call from a visible activity (foreground-service start rules). */
        fun start(context: Context, request: ShareRequest, route: Route) {
            val processor = context.container.processor
            processor.saveJob(CaptureJob(request, route, context.container.store.filesDir(request.captureId)))
            restart(context, request.captureId)
        }

        /** (Re)processes a capture whose job is saved. */
        fun restart(context: Context, captureId: String) {
            context.container.store.updateState(captureId) { it.copy(status = CaptureStatus.EXTRACTING, error = null) }
            context.startForegroundService(Intent(context, CaptureService::class.java).putExtra(EXTRA_ID, captureId))
        }
    }
}
