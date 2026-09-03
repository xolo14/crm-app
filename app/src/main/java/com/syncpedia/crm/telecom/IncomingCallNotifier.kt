package com.syncpedia.crm.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.syncpedia.crm.R
import com.syncpedia.crm.ui.InCallActivity

/**
 * Full-screen incoming-call notification — required when Syncpedia is the
 * default dialer so the ring UI appears over the lock screen / other apps.
 */
object IncomingCallNotifier {

    const val CHANNEL_ID = "incoming_calls"
    const val NOTIF_ID = 2201

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming phone calls"
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun show(ctx: Context, phone: String, name: String) {
        ensureChannel(ctx)
        val fullScreen = Intent(ctx, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(InCallActivity.EXTRA_PHONE, phone)
            putExtra(InCallActivity.EXTRA_NAME, name.ifBlank { phone })
            putExtra(InCallActivity.EXTRA_STATE, android.telecom.Call.STATE_RINGING)
            putExtra(InCallActivity.EXTRA_INCOMING, true)
        }
        val fullPi = PendingIntent.getActivity(
            ctx,
            1,
            fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerPi = PendingIntent.getBroadcast(
            ctx,
            2,
            Intent(ctx, IncomingCallActionReceiver::class.java).setAction(IncomingCallActionReceiver.ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePi = PendingIntent.getBroadcast(
            ctx,
            3,
            Intent(ctx, IncomingCallActionReceiver::class.java).setAction(IncomingCallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = name.ifBlank { phone.ifBlank { "Incoming call" } }
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_incoming)
            .setContentTitle(title)
            .setContentText(if (phone.isNotBlank() && phone != title) phone else "Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(R.drawable.ic_call, "Answer", answerPi)
            .addAction(R.drawable.ic_close, "Decline", declinePi)
            .setTimeoutAfter(60_000L)
            .build()
        notif.flags = notif.flags or Notification.FLAG_INSISTENT
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }
}
