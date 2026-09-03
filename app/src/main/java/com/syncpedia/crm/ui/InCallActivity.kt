package com.syncpedia.crm.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.ActivityInCallBinding
import com.syncpedia.crm.telecom.IncomingCallNotifier
import com.syncpedia.crm.telecom.SyncpediaInCallService
import com.syncpedia.crm.telecom.telecomState
import java.lang.ref.WeakReference

/** Full in-call UI: mute, speaker, keypad (DTMF), hold, record — like a normal Phone dialer. */
class InCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_NAME = "name"
        const val EXTRA_STATE = "state"
        const val EXTRA_INCOMING = "incoming"

        private var ref: WeakReference<InCallActivity>? = null

        fun updateUi(state: Int) {
            ref?.get()?.applyState(state)
        }

        fun refreshAudioUi() {
            ref?.get()?.paintAudioButtons()
        }

        fun refreshRecordingUi() {
            ref?.get()?.paintRecordingButton()
        }

        fun onRecordingStarted(sourceName: String) {
            ref?.get()?.handleRecordingStarted(sourceName)
        }

        fun finishIfOpen() {
            ref?.get()?.let { act ->
                if (!act.isFinishing) act.finish()
            }
        }
    }

    private lateinit var binding: ActivityInCallBinding
    private var connectedAt = 0L
    private var keypadOpen = false
    private val dtmfBuffer = StringBuilder()
    private var recordingPulse: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (connectedAt <= 0L) return
            val sec = ((SystemClock.elapsedRealtime() - connectedAt) / 1000).toInt()
            binding.tvCallTimer.text = "%02d:%02d".format(sec / 60, sec % 60)
            paintRecordingButton()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ref = WeakReference(this)

        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
            .ifBlank { SyncpediaInCallService.phoneNumber }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
            .ifBlank { SyncpediaInCallService.displayName }
            .ifBlank { phone }
        binding.tvCallName.text = name
        binding.tvCallNumber.text = phone
        binding.tvCallAvatar.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        binding.btnEndCall.setOnClickListener {
            SyncpediaInCallService.hangUp()
            IncomingCallNotifier.cancel(this)
            finish()
        }
        binding.btnAnswer.setOnClickListener {
            SyncpediaInCallService.answer()
            IncomingCallNotifier.cancel(this)
            intent.putExtra(EXTRA_INCOMING, false)
            applyState(Call.STATE_ACTIVE)
        }
        binding.btnDecline.setOnClickListener {
            SyncpediaInCallService.hangUp()
            IncomingCallNotifier.cancel(this)
            finish()
        }

        binding.btnMuteWrap.setOnClickListener {
            SyncpediaInCallService.setMute(!SyncpediaInCallService.isMuted)
            paintAudioButtons()
        }
        binding.btnSpeakerWrap.setOnClickListener {
            SyncpediaInCallService.toggleSpeaker()
            paintAudioButtons()
        }
        binding.btnHoldWrap.setOnClickListener {
            SyncpediaInCallService.toggleHold()
            paintAudioButtons()
        }
        binding.btnKeypadWrap.setOnClickListener { toggleKeypad() }
        binding.btnRecWrap.setOnClickListener { onRecordTapped() }

        setupDtmf()

        val state = intent.getIntExtra(
            EXTRA_STATE,
            SyncpediaInCallService.currentCall?.telecomState() ?: Call.STATE_DIALING
        )
        applyState(state)
        paintAudioButtons()
        paintRecordingButton()
    }

    private fun onRecordTapped() {
        val state = SyncpediaInCallService.currentCall?.telecomState()
        if (state != Call.STATE_ACTIVE && state != Call.STATE_HOLDING) {
            Toast.makeText(this, "Connect the call first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SyncpediaInCallService.hasMicPermission(this)) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            return
        }
        val turningOn = !SyncpediaInCallService.recordingDesired
        val ok = SyncpediaInCallService.toggleRecording()
        if (!ok) {
            Toast.makeText(this, "Could not change recording", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            if (turningOn) "Recording on" else "Recording off",
            Toast.LENGTH_SHORT
        ).show()
        paintRecordingButton()
    }

    private fun handleRecordingStarted(@Suppress("UNUSED_PARAMETER") sourceName: String) {
        if (!::binding.isInitialized || isFinishing) return
        binding.tvRecordingBadge.text = "● REC"
        binding.tvRecordingBadge.visibility = View.VISIBLE
        startRecordingPulse()
        paintAudioButtons()
    }

    private fun startRecordingPulse() {
        recordingPulse?.cancel()
        val pulse = ObjectAnimator.ofFloat(binding.tvRecordingBadge, View.ALPHA, 1f, 0.2f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        pulse.start()
        recordingPulse = pulse
        binding.tvRecordingBadge.tag = pulse
    }

    private fun stopRecordingPulse() {
        recordingPulse?.cancel()
        recordingPulse = null
        if (::binding.isInitialized) {
            binding.tvRecordingBadge.alpha = 1f
            binding.tvRecordingBadge.tag = null
        }
    }

    private fun setupDtmf() {
        val map = listOf(
            binding.dtmf1 to '1',
            binding.dtmf2 to '2',
            binding.dtmf3 to '3',
            binding.dtmf4 to '4',
            binding.dtmf5 to '5',
            binding.dtmf6 to '6',
            binding.dtmf7 to '7',
            binding.dtmf8 to '8',
            binding.dtmf9 to '9',
            binding.dtmfStar to '*',
            binding.dtmf0 to '0',
            binding.dtmfHash to '#'
        )
        map.forEach { (btn, digit) ->
            btn.setOnClickListener {
                SyncpediaInCallService.playDtmf(digit)
                if (dtmfBuffer.length < 24) dtmfBuffer.append(digit)
                binding.tvDtmfDigits.visibility = View.VISIBLE
                binding.tvDtmfDigits.text = dtmfBuffer.toString()
            }
        }
    }

    private fun toggleKeypad() {
        keypadOpen = !keypadOpen
        binding.panelDtmf.visibility = if (keypadOpen) View.VISIBLE else View.GONE
        binding.tvCallAvatar.visibility = if (keypadOpen) View.GONE else View.VISIBLE
        binding.btnKeypad.setBackgroundResource(
            if (keypadOpen) R.drawable.bg_incall_btn_on else R.drawable.bg_incall_btn
        )
    }

    private fun paintAudioButtons() {
        val muted = SyncpediaInCallService.isMuted
        val speaker = SyncpediaInCallService.isSpeakerOn
        val holding = SyncpediaInCallService.currentCall?.telecomState() == Call.STATE_HOLDING

        binding.btnMute.setBackgroundResource(
            if (muted) R.drawable.bg_incall_btn_on else R.drawable.bg_incall_btn
        )
        binding.tvMuteLabel.text = if (muted) "Unmute" else "Mute"

        binding.btnSpeaker.setBackgroundResource(
            if (speaker) R.drawable.bg_incall_btn_on else R.drawable.bg_incall_btn
        )
        binding.tvSpeakerLabel.text = if (speaker) "Speaker on" else "Speaker"
        binding.btnSpeaker.isEnabled = true
        binding.btnSpeakerWrap.isEnabled = true
        binding.btnSpeaker.alpha = 1f

        binding.btnHold.setBackgroundResource(
            if (holding) R.drawable.bg_incall_btn_on else R.drawable.bg_incall_btn
        )
        binding.tvHoldLabel.text = if (holding) "Resume" else "Hold"
    }

    private fun paintRecordingButton() {
        if (!::binding.isInitialized || isFinishing) return
        val desired = SyncpediaInCallService.recordingDesired
        val capturing = SyncpediaInCallService.isCapturing
        val on = desired || capturing

        binding.btnRec.setBackgroundResource(
            if (on) R.drawable.bg_incall_btn_on else R.drawable.bg_incall_btn
        )

        when {
            capturing -> {
                val start = SyncpediaInCallService.recordingSegmentStartElapsed
                val sec = if (start > 0L) {
                    ((SystemClock.elapsedRealtime() - start) / 1000L).toInt().coerceAtLeast(0)
                } else {
                    0
                }
                val clock = "%02d:%02d".format(sec / 60, sec % 60)
                binding.btnRec.text = "●"
                binding.tvRecLabel.text = clock
                binding.tvRecordingBadge.visibility = View.VISIBLE
                val src = SyncpediaInCallService.activeSourceName.take(14)
                binding.tvRecordingBadge.text =
                    if (src.isNotBlank()) {
                        "● Rec $clock · $src"
                    } else {
                        "● Recording $clock"
                    }
                if (recordingPulse == null) startRecordingPulse()
            }
            desired -> {
                binding.btnRec.text = "Rec"
                binding.tvRecLabel.text = "Starting…"
                binding.tvRecordingBadge.visibility = View.VISIBLE
                binding.tvRecordingBadge.text = "● Starting recording…"
            }
            else -> {
                binding.btnRec.text = "Rec"
                binding.tvRecLabel.text = "Record"
                binding.tvRecordingBadge.visibility = View.GONE
                stopRecordingPulse()
            }
        }
    }

    private fun applyState(state: Int) {
        val incoming = state == Call.STATE_RINGING ||
            (intent.getBooleanExtra(EXTRA_INCOMING, false) &&
                state != Call.STATE_ACTIVE &&
                state != Call.STATE_DISCONNECTED)

        when (state) {
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                binding.tvCallStatus.text = "Calling…"
                showActiveControls()
            }
            Call.STATE_RINGING -> {
                binding.tvCallStatus.text = "Incoming call"
                showIncomingControls()
            }
            Call.STATE_ACTIVE -> {
                binding.tvCallStatus.text = "Connected"
                intent.putExtra(EXTRA_INCOMING, false)
                showActiveControls()
                IncomingCallNotifier.cancel(this)
                if (connectedAt == 0L) {
                    connectedAt = SystemClock.elapsedRealtime()
                    binding.tvCallTimer.visibility = View.VISIBLE
                    handler.post(tick)
                }
                paintRecordingButton()
            }
            Call.STATE_HOLDING -> {
                binding.tvCallStatus.text = "On hold"
                showActiveControls()
            }
            Call.STATE_DISCONNECTED -> {
                binding.tvCallStatus.text = "Call ended"
                IncomingCallNotifier.cancel(this)
                handler.postDelayed({ if (!isFinishing) finish() }, 600)
            }
            else -> {
                binding.tvCallStatus.text = if (incoming) "Incoming call" else "In call"
                if (incoming) showIncomingControls() else showActiveControls()
            }
        }
        paintAudioButtons()
        paintRecordingButton()
    }

    private fun showIncomingControls() {
        binding.rowCallTools.visibility = View.GONE
        binding.rowIncomingActions.visibility = View.VISIBLE
        binding.panelDtmf.visibility = View.GONE
        binding.tvCallAvatar.visibility = View.VISIBLE
        binding.tvDtmfDigits.visibility = View.GONE
        keypadOpen = false
    }

    private fun showActiveControls() {
        binding.rowCallTools.visibility = View.VISIBLE
        binding.rowIncomingActions.visibility = View.GONE
        binding.panelDtmf.visibility = if (keypadOpen) View.VISIBLE else View.GONE
        binding.tvCallAvatar.visibility = if (keypadOpen) View.GONE else View.VISIBLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyState(
            intent.getIntExtra(
                EXTRA_STATE,
                SyncpediaInCallService.currentCall?.telecomState() ?: Call.STATE_ACTIVE
            )
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        stopRecordingPulse()
        if (::binding.isInitialized) {
            (binding.tvRecordingBadge.tag as? ObjectAnimator)?.cancel()
        }
        if (ref?.get() === this) ref = null
        super.onDestroy()
    }
}
