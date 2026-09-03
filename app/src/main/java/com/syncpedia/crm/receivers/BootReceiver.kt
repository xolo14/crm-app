package com.syncpedia.crm.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.syncpedia.crm.workers.NotificationWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Notification polling only; call upload is scheduled ~10s after each hangup.
        NotificationWorker.schedule(context.applicationContext)
    }
}
