package com.syncpedia.crm.telecom

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.syncpedia.crm.R
import com.syncpedia.crm.ui.InCallActivity
import com.syncpedia.crm.utils.AppCallStore
import com.syncpedia.crm.utils.AppCallTracker
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.CallRecorder
import com.syncpedia.crm.utils.ContactReader
import com.syncpedia.crm.utils.DefaultDialerHelper
import com.syncpedia.crm.utils.SimRecordingPrefs
import com.syncpedia.crm.workers.SyncWorker
import java.io.File
import java.io.IOException

/**
 * System InCall UI + the ONLY call recorder (one MediaRecorder per call).
 * CallRecordingService was removed to stop dual-recorder races / silent files.
 */
class SyncpediaInCallService : InCallService() {

    companion object {
        private const val TAG = "SyncpediaInCall"
        private const val PREFS = "sp_recording_config"
        private const val KEY_SRC = "working_source"
        private const val NOTIF_CHANNEL = "recording_channel"
        private const val NOTIF_ID = 1001

        /** Prefer VOICE_CALL → VOICE_DOWNLINK → MIC (speaker air-capture). */
        private val SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.MIC
        )

        @Volatile var currentCall: Call? = null
            private set
        @Volatile var displayName: String = ""
        @Volatile var phoneNumber: String = ""
        @Volatile var isIncoming: Boolean = false
            private set
        @Volatile var currentSimKey: String = "default"
            private set
        @Volatile var recordingDesired: Boolean = false
            private set
        @Volatile var recordingSegmentStartElapsed: Long = 0L
            private set
        @Volatile var isMuted: Boolean = false
            private set
        @Volatile var isSpeakerOn: Boolean = false
            private set
        @Volatile var isCapturing: Boolean = false
            private set
        @Volatile var activeSourceName: String = ""
            private set

        private var callStartMs = 0L
        private val finishedCallIds = mutableSetOf<Int>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val recordStartToken = Any()

        @Volatile private var instance: SyncpediaInCallService? = null

        @Volatile private var userRejected = false

        fun hangUp() {
            val c = currentCall ?: return
            try {
                when (c.telecomState()) {
                    Call.STATE_RINGING -> {
                        userRejected = true
                        c.reject(false, null)
                    }
                    else -> c.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "hangUp: ${e.message}")
            }
        }

        fun answer() {
            try {
                currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
            } catch (e: Exception) {
                Log.e(TAG, "answer: ${e.message}")
            }
        }

        fun setMute(muted: Boolean): Boolean {
            return try {
                instance?.setMuted(muted)
                isMuted = muted
                true
            } catch (e: Exception) {
                Log.e(TAG, "mute: ${e.message}")
                false
            }
        }

        fun toggleSpeaker(): Boolean {
            val next = !isSpeakerOn
            return try {
                val route = if (next) {
                    android.telecom.CallAudioState.ROUTE_SPEAKER
                } else {
                    android.telecom.CallAudioState.ROUTE_EARPIECE
                }
                @Suppress("DEPRECATION")
                instance?.setAudioRoute(route)
                isSpeakerOn = next
                true
            } catch (e: Exception) {
                Log.e(TAG, "speaker: ${e.message}")
                false
            }
        }

        fun toggleHold(): Boolean {
            val c = currentCall ?: return false
            return try {
                when (c.telecomState()) {
                    Call.STATE_HOLDING -> {
                        c.unhold()
                        true
                    }
                    Call.STATE_ACTIVE -> {
                        c.hold()
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.e(TAG, "hold: ${e.message}")
                false
            }
        }

        fun playDtmf(digit: Char) {
            try {
                currentCall?.playDtmfTone(digit)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        currentCall?.stopDtmfTone()
                    } catch (_: Exception) {
                    }
                }, 160L)
            } catch (e: Exception) {
                Log.e(TAG, "dtmf: ${e.message}")
            }
        }

        fun toggleRecording(): Boolean = setRecordingDesired(!recordingDesired)

        fun setRecordingDesired(enabled: Boolean): Boolean {
            val svc = instance ?: return false
            recordingDesired = enabled
            if (enabled) {
                svc.startRecordingInternal()
            } else {
                svc.stopRecordingKeepFile()
            }
            InCallActivity.refreshRecordingUi()
            return true
        }

        fun hasMicPermission(ctx: android.content.Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var activeSource = -1
    private var pendingRecordingFile: File? = null
    private var answeredMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        releaseRecorder()
        hideRecordingNotification()
        if (instance === this) instance = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState == null) return
        isMuted = audioState.isMuted
        isSpeakerOn = audioState.route == android.telecom.CallAudioState.ROUTE_SPEAKER
        InCallActivity.refreshAudioUi()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d(TAG, "state=$state")
            when (state) {
                Call.STATE_RINGING -> {
                    isIncoming = true
                    showIncomingUi()
                    InCallActivity.updateUi(state)
                }
                Call.STATE_ACTIVE -> {
                    if (callStartMs == 0L) callStartMs = System.currentTimeMillis()
                    IncomingCallNotifier.cancel(this@SyncpediaInCallService)
                    // STATE_ACTIVE also fires after un-hold; only the first one is the answer.
                    if (answeredMs == 0L) {
                        answeredMs = System.currentTimeMillis()
                        recordingDesired = SimRecordingPrefs.isSimAllowed(
                            this@SyncpediaInCallService,
                            currentSimKey
                        )
                    }
                    if (recordingDesired && recorder == null) {
                        scheduleRecordingStarts()
                    } else if (!recordingDesired && recorder == null) {
                        cancelPendingRecordingStarts()
                        CallRecorder.setStatusPublic(
                            this@SyncpediaInCallService,
                            "Recording off for this SIM — tap Rec to start"
                        )
                    }
                    InCallActivity.refreshRecordingUi()
                    InCallActivity.updateUi(state)
                }
                Call.STATE_DISCONNECTING -> {
                    cancelPendingRecordingStarts()
                    if (recorder != null) {
                        pendingRecordingFile = stopRecorderValidated()
                    }
                    InCallActivity.updateUi(state)
                }
                Call.STATE_DISCONNECTED -> finishCall(call)
                else -> InCallActivity.updateUi(state)
            }
        }
    }

    private fun scheduleRecordingStarts() {
        cancelPendingRecordingStarts()
        for (d in longArrayOf(400L, 1200L, 2500L)) {
            mainHandler.postAtTime(
                { if (recordingDesired && recorder == null) startRecordingInternal() },
                recordStartToken,
                SystemClock.uptimeMillis() + d
            )
        }
    }

    private fun cancelPendingRecordingStarts() {
        mainHandler.removeCallbacksAndMessages(recordStartToken)
    }

    override fun onCallAdded(call: Call) {
        currentCall = call
        phoneNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
        isIncoming = isIncomingCall(call)
        displayName = resolveName(phoneNumber)
        currentSimKey = AppDialer.simKeyForHandle(this, call.details?.accountHandle)
        call.registerCallback(callback)
        callStartMs = System.currentTimeMillis()
        answeredMs = 0L
        userRejected = false
        pendingRecordingFile = null
        recordingDesired = false
        recordingSegmentStartElapsed = 0L

        if (!isIncoming && phoneNumber.isNotBlank()) {
            if (!AppCallTracker.hasRecentPlacement(this, phoneNumber)) {
                AppCallTracker.markPlaced(this, phoneNumber, displayName, currentSimKey)
            }
        }

        if (isIncoming || call.telecomState() == Call.STATE_RINGING) {
            showIncomingUi()
        } else {
            launchInCallUi(call.telecomState(), incoming = false)
        }

        if (call.telecomState() == Call.STATE_ACTIVE) {
            answeredMs = System.currentTimeMillis()
            recordingDesired = SimRecordingPrefs.isSimAllowed(this, currentSimKey)
            if (recordingDesired) scheduleRecordingStarts()
        }
    }

    override fun onCallRemoved(call: Call) {
        cancelPendingRecordingStarts()
        finishCall(call)
    }

    private fun isIncomingCall(call: Call): Boolean {
        if (call.telecomState() == Call.STATE_RINGING) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                call.details?.callDirection == Call.Details.DIRECTION_INCOMING
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    private fun resolveName(phone: String): String {
        if (phone.isBlank()) return "Unknown"
        val fromTracker = AppCallTracker.latestPendingName(this, phone)
        if (fromTracker.isNotBlank() && fromTracker != phone) return fromTracker
        return try {
            ContactReader.lookupName(this, phone)?.takeIf { it.isNotBlank() } ?: phone
        } catch (_: Exception) {
            phone
        }
    }

    private fun showIncomingUi() {
        IncomingCallNotifier.show(this, phoneNumber, displayName)
        launchInCallUi(Call.STATE_RINGING, incoming = true)
    }

    private fun launchInCallUi(state: Int, incoming: Boolean) {
        try {
            startActivity(
                Intent(this, InCallActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                    putExtra(InCallActivity.EXTRA_PHONE, phoneNumber)
                    putExtra(InCallActivity.EXTRA_NAME, displayName)
                    putExtra(InCallActivity.EXTRA_STATE, state)
                    putExtra(InCallActivity.EXTRA_INCOMING, incoming)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "launchInCallUi: ${e.message}")
            if (incoming) IncomingCallNotifier.show(this, phoneNumber, displayName)
        }
    }

    private fun startRecordingInternal() {
        if (!recordingDesired) return
        if (recorder != null) return
        val state = currentCall?.telecomState()
        if (state != Call.STATE_ACTIVE && state != Call.STATE_HOLDING) return

        // Hard gate: never record without consent, regardless of how this call path was reached
        // (SIM prefs, UI toggle, scheduled retry). Must be enforced here, not just in navigation.
        if (!com.syncpedia.crm.utils.ConsentPrefs.hasAccepted(this)) {
            Log.w(TAG, "Recording blocked — consent not accepted")
            recordingDesired = false
            InCallActivity.refreshRecordingUi()
            return
        }

        if (!DefaultDialerHelper.isDefaultDialer(this)) {
            CallRecorder.setStatusPublic(
                this,
                "Not default Phone app — set it in More before recording works."
            )
            return
        }
        if (!hasMicPermission(this)) {
            CallRecorder.setStatusPublic(
                this,
                "Microphone permission denied — enable it for Syncpedia CRM"
            )
            recordingDesired = false
            InCallActivity.refreshRecordingUi()
            return
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getInt(KEY_SRC, -1)
        val order = if (saved >= 0 && SOURCES.contains(saved)) {
            listOf(saved) + SOURCES.filter { it != saved }
        } else {
            SOURCES
        }

        // Android 11+ only delivers mic audio in the background if the microphone foreground
        // service is already running, so start it before the recorder.
        showRecordingNotification()
        for (src in order) {
            if (trySource(src)) {
                activeSource = src
                activeSourceName = sourceLabel(src)
                prefs.edit().putInt(KEY_SRC, src).apply()
                isCapturing = true
                recordingSegmentStartElapsed = SystemClock.elapsedRealtime()
                CallRecorder.setStatusPublic(this, "Recording · $activeSourceName")
                InCallActivity.onRecordingStarted(activeSourceName)
                InCallActivity.refreshRecordingUi()
                Log.i(TAG, "Recording source=$src file=${outputFile?.name}")
                return
            }
        }
        hideRecordingNotification()
        Log.e(TAG, "All sources failed — no recording on this device")
        CallRecorder.setStatusPublic(this, "Could not find an audible recording source")
        recordingDesired = false
        InCallActivity.refreshRecordingUi()
    }

    private fun trySource(src: Int): Boolean {
        return try {
            val dir = CallRecorder.getRecordingsDir(this)
            val f = File(dir, "rec_${System.currentTimeMillis()}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(src)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(64000)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = f
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "src=$src SecurityException: ${e.message}")
            releaseRecorder()
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "src=$src RuntimeException: ${e.message}")
            releaseRecorder()
            false
        } catch (e: IOException) {
            Log.w(TAG, "src=$src IOException: ${e.message}")
            releaseRecorder()
            false
        }
    }

    private fun stopRecordingKeepFile() {
        cancelPendingRecordingStarts()
        val file = stopRecorderValidated()
        if (file != null) pendingRecordingFile = file
        recordingSegmentStartElapsed = 0L
        hideRecordingNotification()
        InCallActivity.refreshRecordingUi()
    }

    private fun stopRecorderValidated(): File? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            isCapturing = false
            val f = outputFile
            outputFile = null
            if (f != null && f.exists() && f.length() > 2048L) {
                CallRecorder.registerExternal(
                    this,
                    f,
                    phoneNumber,
                    displayName,
                    sourceLabel(activeSource)
                )
                f
            } else {
                Log.w(TAG, "Recording silent (${f?.length()}B) — resetting source pref")
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_SRC).apply()
                f?.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: ${e.message}")
            releaseRecorder()
            null
        } finally {
            activeSource = -1
            activeSourceName = ""
        }
    }

    private fun finishCall(call: Call) {
        val callKey = System.identityHashCode(call)
        if (!finishedCallIds.add(callKey)) return
        if (finishedCallIds.size > 20) {
            finishedCallIds.clear()
            finishedCallIds.add(callKey)
        }

        cancelPendingRecordingStarts()
        try {
            call.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        IncomingCallNotifier.cancel(this)

        val durationSec = if (callStartMs > 0) {
            ((System.currentTimeMillis() - callStartMs) / 1000L).toInt()
        } else {
            0
        }

        val recordingFile = pendingRecordingFile
            ?: if (recorder != null) stopRecorderValidated() else null
        pendingRecordingFile = null
        hideRecordingNotification()

        val ended = System.currentTimeMillis()
        val started = if (callStartMs > 0) callStartMs else ended
        val phone = phoneNumber
        val incoming = isIncoming

        var deviceCallId = 0L
        if (phone.isNotBlank()) {
            val pending = if (!incoming) {
                AppCallTracker.consumeRecentPlacement(this, phone)
            } else {
                null
            }
            val startMs = when {
                pending != null && pending.placedAtMs > 0 ->
                    minOf(pending.placedAtMs, started)
                callStartMs > 0 -> callStartMs
                else -> ended
            }
            val contact = displayName.ifBlank { pending?.displayName.orEmpty() }
            val wasAnswered = answeredMs > 0L
            val callTypeInt = when {
                !incoming -> CallLog.Calls.OUTGOING_TYPE
                wasAnswered -> CallLog.Calls.INCOMING_TYPE
                userRejected || call.details?.disconnectCause?.code == DisconnectCause.REJECTED ->
                    CallLog.Calls.REJECTED_TYPE
                else -> CallLog.Calls.MISSED_TYPE
            }
            // Talk time starts when the call was answered; unanswered calls have zero duration.
            val recStart = if (wasAnswered) answeredMs else startMs
            val recEnd = if (wasAnswered) ended else startMs
            val item = AppCallStore.recordCompleted(
                ctx = applicationContext,
                phone = phone.ifBlank { pending?.digits.orEmpty() },
                contactName = contact,
                startedAtMs = recStart,
                endedAtMs = recEnd,
                callType = callTypeInt
            )
            deviceCallId = item?.id ?: 0L
            if (item != null) {
                val recPath = recordingFile?.absolutePath
                AppCallStore.mirrorToRoom(applicationContext, item, ended, recPath)
                // One upload, 1 minute after hangup. Manual Sync Now still runs immediately.
                SyncWorker.scheduleAfterCallEnded(applicationContext)
                com.syncpedia.crm.receivers.CallSyncAlarmReceiver.scheduleInOneMinute(applicationContext)
            }
        } else if (!incoming) {
            AppCallTracker.consumeRecentPlacement(this, phone)
        }

        if (currentCall === call) currentCall = null
        callStartMs = 0L
        answeredMs = 0L
        userRejected = false
        isIncoming = false
        currentSimKey = "default"
        isMuted = false
        isSpeakerOn = false
        recordingDesired = false
        recordingSegmentStartElapsed = 0L
        isCapturing = false
        activeSourceName = ""
        InCallActivity.finishIfOpen()
        Log.d(TAG, "finishCall phone=$phone dur≈${durationSec}s file=${recordingFile?.name}")
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        isCapturing = false
        try {
            outputFile?.delete()
        } catch (_: Exception) {
        }
        outputFile = null
    }

    private fun showRecordingNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL,
                "Call Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active call recording notification"
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Syncpedia CRM")
            .setContentText("Call recording active — syncing to CRM")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground: ${e.message}")
        }
    }

    private fun hideRecordingNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun sourceLabel(src: Int): String = when (src) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "SRC_$src"
    }
}
