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
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.DefaultDialerHelper
import com.syncpedia.crm.utils.SimRecordingPrefs
import com.syncpedia.crm.workers.SyncWorker

/**
 * Tracks call lifecycle via PHONE_STATE only (no READ_CALL_LOG — that permission is
 * Play-policy-restricted to default dialer/assistant apps, so we never declare it).
 * Recording audio is owned exclusively by SyncpediaInCallService (default dialer).
 *
 * Call *details* (number/duration/type), however, are logged here even when Syncpedia
 * is not the default dialer and the call wasn't placed from the app:
 *  - Incoming calls carry their number in EXTRA_INCOMING_NUMBER, gated only by the
 *    normal READ_PHONE_STATE permission — no default-dialer role needed.
 *  - Outgoing calls dialed from the stock dialer have no number available to a
 *    non-default-dialer app without READ_CALL_LOG, so those still require either the
 *    app's own dialer (AppCallTracker) or default-dialer status (SyncpediaInCallService).
 * Every call logged here is filtered through SimRecordingPrefs so only the SIM(s) the
 * user selected get synced, matching the behavior already used for recording.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private var callStartMs = 0L
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var lastNumber = ""
        private var activeFromApp = false
        private var activeDisplayName = ""
        private var activeWasIncoming = false
        private var activeAnswered = false
        private var activeSimKey: String? = null
    }

    /**
     * Best-effort SIM identification for this broadcast. Android doesn't guarantee a
     * subscription extra on PHONE_STATE across all OEMs, so unresolved falls through to
     * SimRecordingPrefs' own "unknown SIM" handling (allowed only if every known SIM is
     * enabled, same as the recording path).
     */
    private fun resolveSimKey(context: Context, intent: Intent): String? {
        val subId = intent.getIntExtra("subscription", -1)
            .takeIf { it >= 0 }
            ?: intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1).takeIf { it >= 0 }
        if (subId != null) {
            return SimRecordingPrefs.keyFor(subscriptionId = subId)
        }
        // Single-SIM devices: there's exactly one line, so its key applies unambiguously.
        val lines = AppDialer.listSimLines(context)
        return lines.singleOrNull()?.simKey
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
                activeWasIncoming = true
                activeAnswered = false
                activeSimKey = resolveSimKey(context, intent)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (callStartMs == 0L) callStartMs = System.currentTimeMillis()
                if (number.isNotEmpty()) lastNumber = number
                // Only an outgoing call starts directly in OFFHOOK (no prior RINGING this session).
                if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                    activeWasIncoming = false
                    activeSimKey = resolveSimKey(context, intent)
                } else {
                    activeAnswered = true
                }
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
                    val incoming = activeWasIncoming
                    val answered = activeAnswered
                    val simKey = activeSimKey

                    // When Syncpedia is default dialer, InCallService owns history for every call.
                    val dialerOwns = DefaultDialerHelper.isDefaultDialer(context)
                    if (!dialerOwns) {
                        // Outgoing, app-placed: number came from our own dialer, not the call log.
                        // Incoming: the number is in EXTRA_INCOMING_NUMBER regardless of who dialed
                        // or placed it — no READ_CALL_LOG / default-dialer role required for this.
                        val canLogDetails = fromApp || incoming
                        val simAllowed = simKey == null || SimRecordingPrefs.isSimAllowed(context, simKey)
                        if (canLogDetails && simAllowed && phone.isNotBlank()) {
                            val pending = if (!incoming) AppCallTracker.consumeRecentPlacement(context, phone) else null
                            val name = pending?.displayName?.ifBlank { null }
                                ?: activeDisplayName.ifBlank { null }
                                ?: phone
                            val digits = pending?.digits?.takeIf { it.isNotEmpty() }
                                ?: AppCallTracker.normalize(phone)
                            val startMs = if (started > 0) started else (pending?.placedAtMs ?: endedAt)
                            val dur = ((endedAt - startMs).coerceAtLeast(0L) / 1000L)
                            val callType = when {
                                !incoming -> CallLog.Calls.OUTGOING_TYPE
                                answered -> CallLog.Calls.INCOMING_TYPE
                                else -> CallLog.Calls.MISSED_TYPE
                            }
                            if (dur > 0L || pending != null || incoming) {
                                AppCallStore.recordCompleted(
                                    ctx = context.applicationContext,
                                    phone = phone.ifBlank { digits },
                                    contactName = name,
                                    startedAtMs = startMs,
                                    endedAtMs = if (!incoming || answered) endedAt else startMs,
                                    callType = callType
                                )
                                SyncWorker.scheduleAfterCallEnded(context.applicationContext)
                            }
                        } else if (fromApp) {
                            AppCallTracker.consumeRecentPlacement(context, phone)
                        }
                    } else if (fromApp) {
                        AppCallTracker.consumeRecentPlacement(context, phone)
                    }

                    callStartMs = 0L
                    lastState = TelephonyManager.CALL_STATE_IDLE
                    activeFromApp = false
                    activeDisplayName = ""
                    activeWasIncoming = false
                    activeAnswered = false
                    activeSimKey = null
                    lastNumber = ""
                }
            }
        }
    }
}
