package com.audiosplit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/**
 * The foreground notification. It doubles as the remote control: Pause/Resume and Stop work
 * from the shade without opening the app.
 */
internal object MirrorNotification {

    const val ID = 42
    private const val CHANNEL_ID = "audio_mirror"

    fun build(context: Context, targetLabel: String?, paused: Boolean): Notification {
        ensureChannel(context)
        val where = targetLabel ?: "the second headphones"

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = if (paused) {
            action(context, android.R.drawable.ic_media_play, "Resume", MirrorService.ACTION_RESUME, 2)
        } else {
            action(context, android.R.drawable.ic_media_pause, "Pause", MirrorService.ACTION_PAUSE, 3)
        }
        val stop = action(
            context, android.R.drawable.ic_menu_close_clear_cancel, "Stop", MirrorService.ACTION_STOP, 1
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(if (paused) "Sharing paused" else "Sharing audio")
            .setContentText(if (paused) "Resume to copy to $where again." else "Copying to $where.")
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentIntent(open)
            .addAction(toggle)
            .addAction(stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Show the controls straight away rather than after the system's grace
                    // period for short-lived services.
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun action(
        context: Context,
        icon: Int,
        title: String,
        action: String,
        requestCode: Int,
    ): Notification.Action {
        val intent = PendingIntent.getService(
            context, requestCode,
            Intent(context, MirrorService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), title, intent)
            .build()
    }

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio sharing", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while audio is copied to a second pair of headphones." }
        )
    }
}
