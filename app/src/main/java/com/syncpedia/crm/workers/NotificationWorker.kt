package com.syncpedia.crm.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.syncpedia.crm.utils.NotificationSync
import com.syncpedia.crm.utils.SessionManager
import java.util.concurrent.TimeUnit

/** Polls website notifications periodically (same source as web bell). */
class NotificationWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val UNIQUE = "crm_notification_poll"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!SessionManager.getInstance(applicationContext).isLoggedIn()) {
            return Result.success()
        }
        return try {
            NotificationSync.syncAndNotify(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
