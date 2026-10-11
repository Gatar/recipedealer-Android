package com.gatar.recipedealer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gatar.recipedealer.store.CaptureEntry

object Notifications {
    private const val CH_PROGRESS = "capture_progress"
    private const val CH_RESULT = "capture_result"
    const val PROGRESS_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun progress(context: Context, running: Int): Notification =
        NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_recipe)
            .setContentTitle(context.getString(R.string.notif_progress_title))
            .setContentText(context.resources.getQuantityString(R.plurals.notif_progress_text, running, running))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()

    /** "Saved · <title>": tap → preview; action → send. */
    fun saved(context: Context, entry: CaptureEntry, text: String?) {
        val title = context.getString(R.string.notif_saved, entry.title ?: context.getString(R.string.untitled))
        val b = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_recipe)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openCapture(context, entry.id))
            .setAutoCancel(true)
        if (!context.container.settings.current.autoSend) {
            b.addAction(0, context.getString(R.string.action_send), sendAction(context, entry.id))
        }
        notify(context, entry.id, b.build())
    }

    /** Extraction failed: tap → [tapIntent] (the visible WebView for web pages, else the preview). */
    fun failed(context: Context, entry: CaptureEntry, text: String, tapIntent: PendingIntent? = null) {
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_recipe)
            .setContentTitle(context.getString(R.string.notif_failed, entry.title ?: context.getString(R.string.untitled)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tapIntent ?: openCapture(context, entry.id))
            .setAutoCancel(true)
            .build()
        notify(context, entry.id, n)
    }

    /** 401 / 403 from the backend: the queue is stopped until the token is fixed. */
    fun authNeeded(context: Context) {
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_recipe)
            .setContentTitle(context.getString(R.string.notif_auth_title))
            .setContentText(context.getString(R.string.notif_auth_text))
            .setContentIntent(PendingIntent.getActivity(context, 2, MainActivity.openSettingsIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(AUTH_ID, n)
    }

    private const val AUTH_ID = 2

    fun cancel(context: Context, captureId: String) =
        context.getSystemService(NotificationManager::class.java).cancel(captureId, 0)

    private fun notify(context: Context, captureId: String, n: Notification) {
        // Without POST_NOTIFICATIONS the call is ignored; the capture is still in the list.
        context.getSystemService(NotificationManager::class.java).notify(captureId, 0, n)
    }

    fun openCapture(context: Context, captureId: String): PendingIntent =
        PendingIntent.getActivity(context, captureId.hashCode(), MainActivity.openCaptureIntent(context, captureId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun sendAction(context: Context, captureId: String): PendingIntent =
        PendingIntent.getBroadcast(context, captureId.hashCode(),
            Intent(context, SendReceiver::class.java).putExtra(SendReceiver.EXTRA_ID, captureId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** "Send" action of the result notification. */
class SendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        context.container.send(id)
        Notifications.cancel(context, id)
    }

    companion object {
        const val EXTRA_ID = "captureId"
    }
}
