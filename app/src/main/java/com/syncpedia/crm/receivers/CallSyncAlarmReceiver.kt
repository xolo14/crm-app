package com.syncpedia.crm.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.syncpedia.crm.workers.SyncWorker

/** Backup trigger in case the phone delays WorkManager; the upload itself runs in SyncWorker. */
class CallSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SyncWorker.ensureAutoSync(context.applicationContext)
    }

    companion object {
        fun scheduleInOneMinute(ctx: Context) {
            val app = ctx.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(app, CallSyncAlarmReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(app, 4101, intent, flags)
            val trigger = System.currentTimeMillis() + 60_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }
    }
}
