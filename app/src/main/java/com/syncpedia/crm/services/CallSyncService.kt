package com.syncpedia.crm.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Legacy entry — call sync is handled by SyncWorker (~10s after hangup / Sync Now).
 * Kept so existing intents do not crash.
 */
class CallSyncService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
