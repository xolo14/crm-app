package com.syncpedia.crm.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.syncpedia.crm.R
import com.syncpedia.crm.models.CrmNotification
import com.syncpedia.crm.network.RetrofitClient
import com.syncpedia.crm.ui.NotificationsActivity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pulls the same notifications as the website (GET /notifications.php)
 * and posts Android system alerts for newly seen unread items.
 */
object NotificationSync {

    private const val TAG = "NotificationSync"
    private const val PREFS = "crm_website_notifications"
    private const val KEY_SEEN = "seen_ids"
    private const val KEY_BOOTSTRAPPED = "bootstrapped"
    private const val CHANNEL_ID = "crm_website_notifications"
    private const val NOTIF_BASE = 7100

    suspend fun fetch(ctx: Context): List<CrmNotification> {
        val session = SessionManager.getInstance(ctx)
        if (!session.isLoggedIn()) return emptyList()
        return try {
            val resp = RetrofitClient.api.getNotifications()
            if (!resp.isSuccessful) {
                Log.e(TAG, "GET notifications http=${resp.code()}")
                emptyList()
            } else {
                resp.body().orEmpty()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch: ${e.message}")
            emptyList()
        }
    }

    /** Fetch + show OS notifications for unread items not yet alerted. */
    suspend fun syncAndNotify(ctx: Context): List<CrmNotification> {
        val list = fetch(ctx)
        if (list.isEmpty()) return list
        notifyNewUnread(ctx, list)
        return list
    }

    fun notifyNewUnread(ctx: Context, list: List<CrmNotification>) {
        ensureChannel(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()

        // First run after login: remember existing IDs without spamming the shade
        if (!prefs.getBoolean(KEY_BOOTSTRAPPED, false)) {
            list.forEach { if (it.id.isNotBlank()) seen.add(it.id) }
            prefs.edit()
                .putStringSet(KEY_SEEN, seen)
                .putBoolean(KEY_BOOTSTRAPPED, true)
                .apply()
            return
        }

        val fresh = list.filter { !it.isRead && it.id.isNotBlank() && it.id !in seen }
        if (fresh.isEmpty()) return

        fresh.take(8).forEachIndexed { i, n ->
            postSystemNotification(ctx, n, NOTIF_BASE + i)
            seen.add(n.id)
        }
        fresh.drop(8).forEach { seen.add(it.id) }
        val trimmed = if (seen.size > 400) seen.toList().takeLast(300).toSet() else seen
        prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    fun markSeen(ctx: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        seen.addAll(ids)
        prefs.edit().putStringSet(KEY_SEEN, seen).apply()
    }

    /** Call on logout so the next user gets a clean bootstrap. */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun postSystemNotification(ctx: Context, n: CrmNotification, id: Int) {
        val open = Intent(ctx, NotificationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationsActivity.EXTRA_HIGHLIGHT_ID, n.id)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = n.title?.takeIf { it.isNotBlank() } ?: "Syncpedia CRM"
        val body = n.message?.takeIf { it.isNotBlank() } ?: typeLabel(n.type)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setGroup("crm_website")
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, notif)
        } catch (_: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS denied")
        }
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "CRM notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts from Syncpedia CRM website"
            }
        )
    }

    fun typeLabel(type: String?): String = when (type) {
        "lead_assigned" -> "Lead assigned"
        "task_assigned" -> "Task assigned"
        "task_due" -> "Task due"
        "task_completed" -> "Task completed"
        "follow_up" -> "Follow-up"
        "member_added" -> "Member added"
        "member_removed" -> "Member removed"
        "bulk_action" -> "Bulk action"
        "role_changed" -> "Role change"
        "holiday" -> "Holiday"
        "ops" -> "System"
        "warning" -> "Warning"
        else -> type?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Notification"
    }

    fun formatWhen(createdAt: String?): String {
        if (createdAt.isNullOrBlank()) return ""
        val ms = parseCreatedAt(createdAt) ?: return createdAt.take(16)
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    private fun parseCreatedAt(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(raw.trim())?.time
            } catch (_: Exception) {
            }
        }
        return null
    }
}
