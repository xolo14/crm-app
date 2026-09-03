package com.syncpedia.crm.receivers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.syncpedia.crm.utils.AppCallStore
import com.syncpedia.crm.utils.AppCallTracker
import com.syncpedia.crm.utils.DefaultDialerHelper
import com.syncpedia.crm.workers.SyncWorker

/**
 * Tracks Syncpedia-originated call lifecycle via PHONE_STATE only.
 * Recording is owned exclusively by SyncpediaInCallService (default dialer).
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private var callStartMs = 0L
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var lastNumber = ""
        private var activeFromApp = false
        private var activeDisplayName = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != TelephonyManager.ACTION_PHONE_STATE_CHANGED &&
            action != "android.intent.action.PHONE_STATE"
        ) {
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (number.isNotEmpty()) lastNumber = number
                lastState = TelephonyManager.CALL_STATE_RINGING
                activeFromApp = false
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                callStartMs = System.currentTimeMillis()
                if (number.isNotEmpty()) lastNumber = number
                lastState = TelephonyManager.CALL_STATE_OFFHOOK
                activeFromApp = AppCallTracker.hasRecentPlacement(context, lastNumber)
                if (activeFromApp) {
                    activeDisplayName = AppCallTracker.latestPendingName(context, lastNumber)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState != TelephonyManager.CALL_STATE_IDLE) {
                    val endedAt = System.currentTimeMillis()
                    val started = callStartMs
                    val phone = lastNumber
                    val fromApp = activeFromApp || AppCallTracker.hasRecentPlacement(context, phone)

                    // When Syncpedia is default dialer, InCallService owns history
                    val dialerOwns = DefaultDialerHelper.isDefaultDialer(context)
                    if (!dialerOwns && fromApp) {
                        val pending = AppCallTracker.consumeRecentPlacement(context, phone)
                        val name = pending?.displayName?.ifBlank { null }
                            ?: activeDisplayName.ifBlank { null }
                            ?: phone
                        val digits = pending?.digits?.takeIf { it.isNotEmpty() }
                            ?: AppCallTracker.normalize(phone)
                        val startMs = if (started > 0) started else (pending?.placedAtMs ?: endedAt)
                        val dur = ((endedAt - startMs).coerceAtLeast(0L) / 1000L)
                        if (dur > 0L || pending != null) {
                            AppCallStore.recordCompleted(
                                ctx = context.applicationContext,
                                phone = phone.ifBlank { digits },
                                contactName = name,
                                startedAtMs = startMs,
                                endedAtMs = endedAt,
                                callType = CallLog.Calls.OUTGOING_TYPE
                            )
                            SyncWorker.scheduleAfterCallEnded(context.applicationContext)
                        }
                    } else if (dialerOwns && fromApp) {
                        AppCallTracker.consumeRecentPlacement(context, phone)
                    }

                    callStartMs = 0L
                    lastState = TelephonyManager.CALL_STATE_IDLE
                    activeFromApp = false
                    activeDisplayName = ""
                    lastNumber = ""
                }
            }
        }
    }
}
