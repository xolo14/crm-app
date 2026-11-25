package com.syncpedia.crm.utils

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.syncpedia.crm.models.CallLogItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * Universal call recorder for sideloaded default-dialer builds.
 * Captures PCM via AudioRecord and encodes live MP3 with LAME.
 *
 * Layers (never VOICE_COMMUNICATION):
 *  1. VOICE_CALL — both sides via InCallService / default dialer
 *  2. VOICE_DOWNLINK — remote party (Samsung / Xiaomi / many OEMs)
 *  3. Air-capture sources + speakerphone
 */
object CallRecorder {

    private const val TAG = "CallRecorder"
    private const val PREFS = "call_recordings"
    private const val KEY_FILES = "files"
    private const val MAX_KEEP = 80
    private const val PREFS_STATUS = "call_rec_status"
    private const val KEY_LAST_STATUS = "last_status"
    private const val MAX_SOURCE_SWITCHES = 4
    private const val RECORD_FORMAT = "mp3"

    data class RecordingItem(
        val digits: String,
        val path: String,
        val endedAtMs: Long,
        val contactName: String = "",
        val phone: String = "",
        val uploaded: Boolean = false,
        val startedAtMs: Long = 0L,
        /** Human-readable MediaRecorder source used for this file. */
        val sourceLabel: String = "",
        val peakAmplitude: Int = 0
    )

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val livePeak = AtomicInteger(0)
    private val skippedSources = mutableSetOf<Int>()
    private val failedSourceNotes = mutableListOf<String>()
    private var sourceSwitches = 0
    private val captureLock = Any()
    @Volatile
    private var stopping = false
    private var audioRecord: AudioRecord? = null
    private var mp3Out: FileOutputStream? = null
    private var mp3Stream: Mp3Encoder.Stream? = null
    private var recordThread: Thread? = null
    @Volatile
    private var captureRunning = false
    private var currentFile: File? = null
    private var currentDigits: String = ""
    private var currentPhone: String = ""
    private var currentName: String = ""
    private var currentStartedAt: Long = 0L
    private var activeAudioSource = -1
    private var speakerEnabledByUs = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var appCtx: Context? = null
    /** True when we fell back after VOICE_* failed or was silent. */
    @Volatile
    private var usedFallbackPath = false

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var lastSourceLabel: String = ""
        private set

    @Volatile
    var lastStatusMessage: String = ""
        private set

    fun getRecordingsDir(ctx: Context): File {
        val ext = ctx.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
        val dir = if (ext != null) {
            File(ext, "call_recordings").apply { mkdirs() }
        } else {
            File(ctx.filesDir, "call_recordings").apply { mkdirs() }
        }
        return dir
    }

    fun getDiscardedDir(ctx: Context): File {
        return File(getRecordingsDir(ctx), "discarded").apply { mkdirs() }
    }

    fun hasMicPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun getActiveSourceName(): String =
        if (activeAudioSource >= 0) RecordingConfig.sourceName(activeAudioSource)
        else if (isRecording) lastSourceLabel
        else "Not recording"

    fun hasOpenCapture(): Boolean = synchronized(captureLock) {
        captureRunning || audioRecord != null || currentFile != null
    }

    @Volatile
    private var preferTwoWayCapture: Boolean = true

    fun start(
        ctx: Context,
        phoneHint: String = "",
        displayName: String = "",
        preferTwoWay: Boolean = true
    ): Boolean = synchronized(captureLock) {
        if (stopping) {
            Log.w(TAG, "start ignored — stop in progress")
            return false
        }
        if (isRecording) {
            Log.w(TAG, "Already recording — ignoring start()")
            return true
        }
        if (!hasMicPermission(ctx)) {
            setStatus(ctx, "Microphone permission denied")
            return false
        }
        if (!ConsentPrefs.hasAccepted(ctx)) {
            setStatus(ctx, "Recording blocked — call consent not accepted")
            Log.w(TAG, "start() blocked — consent not accepted")
            return false
        }
        if (!Mp3Encoder.isAvailable()) {
            setStatus(ctx, "MP3 encoder unavailable on this device")
            return false
        }

        preferTwoWayCapture = preferTwoWay
        appCtx = ctx.applicationContext
        currentDigits = AppCallTracker.normalize(phoneHint)
        currentPhone = phoneHint
        currentName = displayName
        currentStartedAt = System.currentTimeMillis()
        speakerEnabledByUs = false
        activeAudioSource = -1
        livePeak.set(0)
        skippedSources.clear()
        failedSourceNotes.clear()
        sourceSwitches = 0
        usedFallbackPath = false
        mainHandler.removeCallbacksAndMessages(null)

        prepareInCallAudio(ctx)
        val ok = beginWithBestSource(ctx)
        if (ok) scheduleSilenceWatchdog(ctx)
        return ok
    }

    /** Alias used by accessibility / test UI. */
    fun startRecording(ctx: Context): Boolean = start(ctx)

    private fun beginWithBestSource(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedSource = prefs.getInt(RecordingConfig.KEY_WORKING_SOURCE, -1)
        val priority = if (preferTwoWayCapture) {
            RecordingConfig.SOURCE_PRIORITY
        } else {
            // Non-dialer / best-effort: air-capture sources only
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC
            )
        }
        val sourcesToTry = buildList {
            if (savedSource == MediaRecorder.AudioSource.MIC ||
                RecordingConfig.isAirCapture(savedSource)
            ) {
                add(savedSource)
            }
            if (preferTwoWayCapture &&
                savedSource >= 0 &&
                !RecordingConfig.isAirCapture(savedSource) &&
                RecordingConfig.SOURCE_PRIORITY.contains(savedSource)
            ) {
                add(savedSource)
            }
            addAll(priority)
        }.distinct().filter { it !in skippedSources }

        val dir = getRecordingsDir(ctx)
        for (source in sourcesToTry) {
            Log.d(TAG, "Trying source: ${RecordingConfig.sourceName(source)}")
            if (RecordingConfig.isAirCapture(source)) {
                prepareAirCaptureAudio(ctx)
            }
            if (tryStartWithSource(ctx, dir, source)) {
                activeAudioSource = source
                lastSourceLabel = RecordingConfig.sourceName(source)
                Log.i(TAG, "Recording started with: ${RecordingConfig.sourceName(source)}")
                announceActiveSource(ctx, source)
                return true
            }
            val note = "${RecordingConfig.shortSourceName(source)} blocked/failed"
            failedSourceNotes.add(note)
            Log.w(TAG, "Source failed (will surface to user): $note")
            skippedSources.add(source)
        }

        restoreSpeakerphone(ctx)
        val failDetail = failedSourceNotes.joinToString(" → ").ifBlank { "unknown" }
        setStatus(ctx, "All audio sources failed ($failDetail)")
        Log.e(TAG, "All audio sources failed on this device: $failDetail")
        return false
    }

    private fun announceActiveSource(ctx: Context, source: Int) {
        val failures = failedSourceNotes.joinToString(", ")
        when {
            RecordingConfig.isAirCapture(source) -> {
                usedFallbackPath = failures.isNotEmpty() || usedFallbackPath
                prepareAirCaptureAudio(ctx)
                val msg = if (failures.isNotEmpty()) {
                    "⚠️ Call-mix blocked ($failures) → ${RecordingConfig.shortSourceName(source)}. " +
                        "Turn Speaker on yourself if the recording is quiet."
                } else {
                    "Recording via ${RecordingConfig.shortSourceName(source)}"
                }
                setStatus(ctx, msg)
                Log.w(TAG, msg)
            }
            source == MediaRecorder.AudioSource.VOICE_DOWNLINK -> {
                usedFallbackPath = true
                val msg = if (failures.isNotEmpty()) {
                    "VOICE_CALL unavailable ($failures) → VOICE_DOWNLINK (remote side only)"
                } else {
                    "Recording… VOICE_DOWNLINK (remote side)"
                }
                setStatus(ctx, msg)
                Log.w(TAG, msg)
            }
            else -> {
                setStatus(ctx, "Recording… ${RecordingConfig.sourceName(source)}")
            }
        }
    }

    /**
     * VOICE_CALL / VOICE_DOWNLINK often start successfully but write silence.
     * After a few seconds with maxAmplitude≈0, switch sources and auto-enable speaker on MIC.
     * Air-capture keeps trying speaker/volume instead of giving up early.
     */
    private fun scheduleSilenceWatchdog(ctx: Context) {
        val threshold = RecordingConfig.silencePeakThreshold(ctx, activeAudioSource)
        var checks = 0
        val poll = object : Runnable {
            override fun run() {
                if (!isRecording) return
                checks++

                if (livePeak.get() >= threshold) {
                    ctx.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(RecordingConfig.KEY_WORKING_SOURCE, activeAudioSource)
                        .apply()
                    Log.i(
                        TAG,
                        "Audible signal confirmed peak=${livePeak.get()} " +
                            "(threshold=$threshold) via $lastSourceLabel"
                    )
                    return
                }

                if (RecordingConfig.isAirCapture(activeAudioSource)) {
                    if (checks >= 10 && livePeak.get() == 0) {
                        setStatus(
                            ctx,
                            "Still silent on mic — turn Speaker on if needed. " +
                                "This phone may block call mix."
                        )
                    }
                    // Keep polling for whole call so we can confirm later peaks
                    mainHandler.postDelayed(this, 700L)
                    return
                }

                if (checks >= 6) {
                    Log.w(
                        TAG,
                        "Silent source $lastSourceLabel peak=${livePeak.get()} " +
                            "threshold=$threshold — switching (user-visible)"
                    )
                    setStatus(
                        ctx,
                        "Silent ${RecordingConfig.shortSourceName(activeAudioSource)} " +
                            "(peak=${livePeak.get()}) → trying next source…"
                    )
                    switchAwayFromSilentSource(ctx)
                    return
                }
                mainHandler.postDelayed(this, 500L)
            }
        }
        mainHandler.postDelayed(poll, 500L)
    }

    private fun switchAwayFromSilentSource(ctx: Context) {
        synchronized(captureLock) {
            if (stopping || !isRecording) return
            if (sourceSwitches >= MAX_SOURCE_SWITCHES) {
                Log.w(TAG, "Max source switches reached — will discard if still silent on stop")
                return
            }
            sourceSwitches++
            if (activeAudioSource >= 0) {
                skippedSources.add(activeAudioSource)
                failedSourceNotes.add(
                    "${RecordingConfig.shortSourceName(activeAudioSource)} silent"
                )
            }

            ctx.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(RecordingConfig.KEY_WORKING_SOURCE)
                .apply()

            val phone = currentPhone
            val name = currentName
            val digits = currentDigits
            val started = currentStartedAt

            abortCurrentCaptureOnly()
            livePeak.set(0)
            currentPhone = phone
            currentName = name
            currentDigits = digits
            currentStartedAt = started

            if (MediaRecorder.AudioSource.MIC !in skippedSources ||
                MediaRecorder.AudioSource.CAMCORDER !in skippedSources ||
                MediaRecorder.AudioSource.VOICE_RECOGNITION !in skippedSources
            ) {
                val dir = getRecordingsDir(ctx)
                val airSources = listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.CAMCORDER,
                    MediaRecorder.AudioSource.MIC
                ).filter { it !in skippedSources }
                for (src in airSources) {
                    prepareAirCaptureAudio(ctx)
                    if (tryStartWithSource(ctx, dir, src)) {
                        activeAudioSource = src
                        lastSourceLabel = RecordingConfig.sourceName(src)
                        usedFallbackPath = true
                        announceActiveSource(ctx, src)
                        scheduleSilenceWatchdog(ctx)
                        return
                    }
                    skippedSources.add(src)
                    failedSourceNotes.add("${RecordingConfig.shortSourceName(src)} blocked/failed")
                }
            }

            if (beginWithBestSource(ctx)) {
                scheduleSilenceWatchdog(ctx)
            } else {
                setStatus(ctx, "Could not find an audible recording source")
            }
        }
    }

    private fun abortCurrentCaptureOnly() {
        mainHandler.removeCallbacksAndMessages(null)
        stopCaptureEngine(deleteFile = true)
        activeAudioSource = -1
    }

    fun stop(ctx: Context): File? = synchronized(captureLock) {
        mainHandler.removeCallbacksAndMessages(null)
        stopping = true
        try {
            if (!isRecording && audioRecord == null && currentFile == null) {
                Log.w(TAG, "stop() called but not recording")
                restoreSpeakerphone(ctx)
                return null
            }

            val peakBeforeStop = livePeak.get()

            if (speakerEnabledByUs) restoreSpeakerphone(ctx)

            return try {
                stopCaptureEngine(deleteFile = false)
                isRecording = false

                val file = currentFile
                val digits = currentDigits
                val phone = currentPhone
                val name = currentName
                val started = currentStartedAt
                val usedSource = activeAudioSource
                currentFile = null
                currentDigits = ""
                currentPhone = ""
                currentName = ""
                currentStartedAt = 0L
                activeAudioSource = -1
                livePeak.set(0)

                val bigEnough = file != null && file.exists() &&
                    file.length() >= RecordingConfig.MIN_FILE_SIZE
                val callMs = System.currentTimeMillis() - started.coerceAtLeast(0L)
                val threshold = RecordingConfig.silencePeakThreshold(ctx, usedSource)
                val fileLen = file?.length() ?: 0L
                // PCM peak is reliable; also keep substantial MP3s from flaky OEMs.
                val audible = when {
                    peakBeforeStop >= threshold -> true
                    fileLen >= RecordingConfig.MIN_KEEP_BYTES -> true
                    callMs >= 3_000L && fileLen >= RecordingConfig.MIN_FILE_SIZE -> true
                    RecordingConfig.isAirCapture(usedSource) &&
                        callMs >= 2_000L &&
                        fileLen >= RecordingConfig.MIN_FILE_SIZE -> true
                    peakBeforeStop > 0 && fileLen >= RecordingConfig.MIN_FILE_SIZE -> true
                    else -> false
                }

                if (bigEnough && audible) {
                    val srcLabel = RecordingConfig.sourceName(usedSource)
                    remember(
                        ctx,
                        RecordingItem(
                            digits = digits,
                            path = file!!.absolutePath,
                            endedAtMs = System.currentTimeMillis(),
                            contactName = name,
                            phone = phone.ifBlank { digits },
                            uploaded = false,
                            startedAtMs = started,
                            sourceLabel = srcLabel,
                            peakAmplitude = peakBeforeStop
                        )
                    )
                    ctx.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(RecordingConfig.KEY_WORKING_SOURCE, usedSource)
                        .apply()
                    val fallbackNote = if (usedFallbackPath) " [fallback]" else ""
                    val silentWarn = if (peakBeforeStop <= 0) {
                        " ⚠️ Audio may be silent — use speaker + high volume next call"
                    } else {
                        ""
                    }
                    setStatus(
                        ctx,
                        "Saved ${file.name} (${formatSize(file.absolutePath)}) via $srcLabel" +
                            " peak=$peakBeforeStop$fallbackNote$silentWarn"
                    )
                    Log.i(
                        TAG,
                        "Recording saved: ${file.name} (${file.length() / 1024}KB, peak=$peakBeforeStop, " +
                            "threshold=$threshold, source: $srcLabel, fallback=$usedFallbackPath)"
                    )
                    usedFallbackPath = false
                    file
                } else {
                    archiveDiscardedRecording(
                        ctx,
                        file,
                        peakBeforeStop,
                        usedSource,
                        threshold,
                        callMs
                    )
                    ctx.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(RecordingConfig.KEY_WORKING_SOURCE)
                        .apply()
                    usedFallbackPath = false
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording exception: ${e.message}")
                setStatus(ctx, "Recording stop failed: ${e.message}")
                releaseQuiet(ctx)
                null
            }
        } finally {
            stopping = false
        }
    }

    /**
     * Keep discarded files under call_recordings/discarded/ for diagnosis —
     * never delete silently.
     */
    private fun archiveDiscardedRecording(
        ctx: Context,
        file: File?,
        peak: Int,
        source: Int,
        threshold: Int,
        callMs: Long
    ) {
        val src = RecordingConfig.shortSourceName(source)
        val size = file?.length() ?: 0L
        val reason =
            "peak=$peak < threshold=$threshold (or empty) size=$size callMs=$callMs source=$src"
        Log.w(TAG, "DISCARD recording — $reason path=${file?.absolutePath}")

        var keptPath = "(none)"
        if (file != null && file.exists()) {
            try {
                val dir = getDiscardedDir(ctx)
                val dest = File(dir, "discarded_${System.currentTimeMillis()}_${file.name}")
                if (file.renameTo(dest)) {
                    keptPath = dest.absolutePath
                } else {
                    file.copyTo(dest, overwrite = true)
                    file.delete()
                    keptPath = dest.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not archive discarded file: ${e.message}")
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }

        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} | $reason | $keptPath"
        RecordingConfig.appendDiscardLog(ctx, line)
        setStatus(
            ctx,
            "Recording discarded ($reason). Kept for diagnosis at discarded/. " +
                "Next call retries auto-detect."
        )
    }

    fun stopRecording(ctx: Context): File? = stop(ctx)

    fun hasAudibleAudio(file: File, livePeakHint: Int = 0): Boolean {
        if (!file.exists() || file.length() < RecordingConfig.MIN_FILE_SIZE) return false
        // Peak hint alone is unreliable on many devices — never reject a large file for peak=0
        if (file.length() >= RecordingConfig.MIN_KEEP_BYTES) return true
        if (livePeakHint >= RecordingConfig.DEFAULT_MIC_SILENCE_PEAK) return true
        return file.length() >= RecordingConfig.MIN_FILE_SIZE
    }

    /**
     * Move mistakenly discarded substantial takes back into the main recordings list.
     */
    fun restoreDiscardedRecordings(ctx: Context): Int {
        val discardedDirs = listOfNotNull(
            getDiscardedDir(ctx),
            File(ctx.filesDir, "call_recordings/discarded").takeIf { it.exists() }
        ).distinctBy { it.absolutePath }
        val mainDir = getRecordingsDir(ctx)
        var restored = 0
        val existing = load(ctx).toMutableList()
        for (discardedDir in discardedDirs) {
            if (!discardedDir.isDirectory) continue
        discardedDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.lowercase() in setOf("mp3", "m4a", "wav") &&
                    it.length() >= RecordingConfig.MIN_KEEP_BYTES
            }
            ?.forEach { f ->
                try {
                    val target = File(mainDir, "restored_${System.currentTimeMillis()}_${f.name}")
                    val moved = f.renameTo(target) || run {
                        f.copyTo(target, overwrite = true)
                        f.delete()
                        true
                    }
                    if (moved && target.exists()) {
                        existing.add(
                            0,
                            RecordingItem(
                                digits = "",
                                path = target.absolutePath,
                                endedAtMs = target.lastModified(),
                                phone = "",
                                contactName = "Restored recording",
                                uploaded = false,
                                startedAtMs = target.lastModified(),
                                sourceLabel = "Restored from discarded",
                                peakAmplitude = 0
                            )
                        )
                        restored++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "restore discarded failed: ${e.message}")
                }
            }
        }
        if (restored > 0) {
            save(ctx, existing.take(80))
            setStatus(ctx, "Restored $restored recording(s) previously discarded")
        }
        return restored
    }

    fun loadLastStatus(ctx: Context): String {
        if (lastStatusMessage.isNotBlank()) return lastStatusMessage
        return ctx.applicationContext
            .getSharedPreferences(PREFS_STATUS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STATUS, "")
            .orEmpty()
    }

    fun setStatusPublic(ctx: Context, msg: String) = setStatus(ctx, msg)

    fun resetSourcePreference(ctx: Context) {
        ctx.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(RecordingConfig.KEY_WORKING_SOURCE)
            .remove(RecordingConfig.KEY_USE_SPEAKER_MODE)
            .apply()
        // Also clear legacy key
        ctx.getSharedPreferences("sp_recorder", Context.MODE_PRIVATE)
            .edit()
            .remove("working_audio_source")
            .apply()
        setStatus(ctx, "Source preference reset — will auto-detect on next call")
        Log.i(TAG, "Source preference reset — will auto-detect on next call")
    }

    private fun setStatus(ctx: Context, msg: String) {
        lastStatusMessage = msg
        try {
            ctx.applicationContext
                .getSharedPreferences(PREFS_STATUS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_STATUS, msg)
                .apply()
        } catch (_: Exception) {
        }
        Log.i(TAG, "status: $msg")
    }

    private fun tryStartWithSource(@Suppress("UNUSED_PARAMETER") ctx: Context, dir: File, audioSource: Int): Boolean {
        if (!Mp3Encoder.isAvailable()) return false
        stopCaptureEngine(deleteFile = true)

        val sampleRate = RecordingConfig.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord buffer invalid for source $audioSource")
            return false
        }

        val file = File(dir, "rec_${System.currentTimeMillis()}.$RECORD_FORMAT")
        var record: AudioRecord? = null
        var out: FileOutputStream? = null
        return try {
            record = AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized for source $audioSource")
                record.release()
                return false
            }
            out = FileOutputStream(file)
            val encoder = Mp3Encoder.Stream(sampleRate, RecordingConfig.BIT_RATE / 1000)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord failed to enter RECORDING for source $audioSource")
                cleanupFailedAttempt(record, out, file)
                return false
            }

            audioRecord = record
            mp3Out = out
            mp3Stream = encoder
            currentFile = file
            captureRunning = true
            isRecording = true

            val bufSize = minBuf
            recordThread = Thread({
                val buf = ShortArray(bufSize)
                val ar = audioRecord
                val stream = mp3Stream
                val fos = mp3Out
                try {
                    while (captureRunning && ar != null && stream != null && fos != null) {
                        val n = try {
                            ar.read(buf, 0, buf.size)
                        } catch (_: Exception) {
                            break
                        }
                        if (n > 0) {
                            var peak = 0
                            for (i in 0 until n) {
                                peak = max(peak, abs(buf[i].toInt()))
                            }
                            livePeak.updateAndGet { cur -> max(cur, peak) }
                            try {
                                stream.encode(buf, n, fos)
                            } catch (e: Exception) {
                                Log.w(TAG, "MP3 encode write: ${e.message}")
                                break
                            }
                        } else if (n < 0) {
                            Log.w(TAG, "AudioRecord.read error=$n")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "record thread: ${e.message}")
                }
            }, "SyncpediaMp3Rec").also { it.start() }

            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException with source $audioSource: ${e.message}")
            cleanupFailedAttempt(record, out, file)
            false
        } catch (e: IOException) {
            Log.w(TAG, "IOException with source $audioSource: ${e.message}")
            cleanupFailedAttempt(record, out, file)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "RuntimeException with source $audioSource: ${e.message}")
            cleanupFailedAttempt(record, out, file)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Exception with source $audioSource: ${e.message}")
            cleanupFailedAttempt(record, out, file)
            false
        }
    }

    private fun stopCaptureEngine(deleteFile: Boolean) {
        captureRunning = false
        val thread = recordThread
        recordThread = null
        try {
            thread?.join(1500L)
        } catch (_: Exception) {
        }

        val out = mp3Out
        val enc = mp3Stream
        mp3Out = null
        mp3Stream = null
        if (out != null && enc != null) {
            try {
                enc.finish(out)
            } catch (e: Exception) {
                Log.w(TAG, "mp3 finish: ${e.message}")
            }
            try {
                out.flush()
                out.close()
            } catch (_: Exception) {
            }
        } else {
            try {
                out?.close()
            } catch (_: Exception) {
            }
        }

        val ar = audioRecord
        audioRecord = null
        try {
            if (ar?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                ar.stop()
            }
        } catch (_: Exception) {
        }
        try {
            ar?.release()
        } catch (_: Exception) {
        }

        isRecording = false
        if (deleteFile) {
            try {
                currentFile?.delete()
            } catch (_: Exception) {
            }
            currentFile = null
        }
    }

    private fun cleanupFailedAttempt(
        record: AudioRecord?,
        out: FileOutputStream?,
        file: File
    ) {
        captureRunning = false
        try {
            out?.close()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        try {
            file.delete()
        } catch (_: Exception) {
        }
        if (audioRecord === record) audioRecord = null
        if (mp3Out === out) mp3Out = null
        mp3Stream = null
        currentFile = null
        isRecording = false
    }

    private fun prepareInCallAudio(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousMode = am.mode
            @Suppress("DEPRECATION")
            previousSpeaker = am.isSpeakerphoneOn
            // Keep telephony mode for VOICE_* sources; air-capture overrides later
            try {
                am.mode = AudioManager.MODE_IN_CALL
            } catch (_: Exception) {
                try {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "prepareInCallAudio: ${e.message}")
        }
    }

    /** Air-capture mode — does not force speaker; user controls Speaker. */
    private fun prepareAirCaptureAudio(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            previousSpeaker = am.isSpeakerphoneOn
            try {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (_: Exception) {
                try {
                    am.mode = AudioManager.MODE_NORMAL
                } catch (_: Exception) {
                }
            }
            Log.i(TAG, "Air-capture audio prepared (no auto-speaker)")
        } catch (e: Exception) {
            Log.e(TAG, "prepareAirCaptureAudio: ${e.message}")
        }
    }

    private fun enableSpeakerphone(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        // No-op: speaker is user-controlled only.
    }

    private fun restoreSpeakerphone(ctx: Context) {
        if (!speakerEnabledByUs) {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = previousMode
            } catch (_: Exception) {
            }
            return
        }
        speakerEnabledByUs = false
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            if (am.isSpeakerphoneOn && !previousSpeaker) {
                am.isSpeakerphoneOn = false
            }
            am.mode = previousMode
            Log.i(TAG, "Speakerphone restored to normal")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore speakerphone: ${e.message}")
        }
    }

    private fun releaseQuiet(ctx: Context) {
        mainHandler.removeCallbacksAndMessages(null)
        if (speakerEnabledByUs) restoreSpeakerphone(ctx)
        stopCaptureEngine(deleteFile = true)
        activeAudioSource = -1
        livePeak.set(0)
    }

    fun listAll(ctx: Context): List<RecordingItem> {
        restoreDiscardedRecordings(ctx)
        purgeSilentFiles(ctx)
        val fromPrefs = load(ctx).filter { File(it.path).exists() && hasAudibleAudio(File(it.path)) }
        val dirs = listOfNotNull(
            getRecordingsDir(ctx),
            File(ctx.filesDir, "call_recordings").takeIf { it.exists() }
        ).distinctBy { it.absolutePath }
        val known = fromPrefs.map { it.path }.toSet()
        val extras = dirs.flatMap { dir ->
            dir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.extension.lowercase() in setOf("mp3", "m4a", "wav") &&
                        it.path !in known &&
                        hasAudibleAudio(it)
                }
                ?.map {
                    RecordingItem(
                        digits = "",
                        path = it.absolutePath,
                        endedAtMs = it.lastModified(),
                        phone = "",
                        contactName = "Recording",
                        uploaded = false,
                        startedAtMs = it.lastModified()
                    )
                }
                .orEmpty()
        }
        return (fromPrefs + extras).sortedByDescending { it.endedAtMs }
    }

    fun purgeSilentFiles(ctx: Context) {
        val kept = mutableListOf<RecordingItem>()
        for (item in load(ctx)) {
            val f = File(item.path)
            if (f.exists() && hasAudibleAudio(f, item.peakAmplitude)) {
                kept.add(item)
            } else if (f.exists()) {
                archiveDiscardedRecording(
                    ctx,
                    f,
                    peak = item.peakAmplitude,
                    source = -1,
                    threshold = RecordingConfig.silencePeakThreshold(ctx),
                    callMs = 0L
                )
            }
        }
        save(ctx, kept)
        val scanDirs = listOfNotNull(
            getRecordingsDir(ctx),
            File(ctx.filesDir, "call_recordings").takeIf { it.exists() }
        ).distinctBy { it.absolutePath }
        for (scanDir in scanDirs) {
            scanDir.listFiles()?.forEach { f ->
                if (f.isFile &&
                    f.extension.lowercase() in setOf("wav", "m4a", "mp3") &&
                    !hasAudibleAudio(f)
                ) {
                    archiveDiscardedRecording(
                        ctx,
                        f,
                        peak = 0,
                        source = -1,
                        threshold = RecordingConfig.silencePeakThreshold(ctx),
                        callMs = 0L
                    )
                }
            }
        }
    }

    fun findForCall(ctx: Context, item: CallLogItem): File? {
        val digits = AppCallTracker.normalize(item.phoneNumber)
        val entries = load(ctx)
        val callEnd = item.date + item.duration * 1000
        val match = entries.firstOrNull { e ->
            val f = File(e.path)
            f.exists() && hasAudibleAudio(f) &&
                (digits.isEmpty() || e.digits.isEmpty() || AppCallTracker.phonesMatch(e.digits, digits)) &&
                kotlin.math.abs(e.endedAtMs - callEnd) < 45 * 60 * 1000L
        } ?: entries.firstOrNull {
            val f = File(it.path)
            f.exists() && hasAudibleAudio(f) &&
                digits.isNotEmpty() && AppCallTracker.phonesMatch(it.digits, digits)
        }
        val f = match?.path?.let { File(it) }
        return if (f != null && f.exists() && hasAudibleAudio(f)) f else null
    }

    fun markUploaded(ctx: Context, path: String) {
        save(ctx, load(ctx).map {
            if (it.path == path) it.copy(uploaded = true) else it
        })
    }

    /** Register a MediaRecorder file produced by SyncpediaInCallService. */
    fun registerExternal(
        ctx: Context,
        file: File,
        phone: String,
        contactName: String,
        sourceLabel: String
    ) {
        if (!file.exists() || file.length() < 1L) return
        remember(
            ctx,
            RecordingItem(
                digits = AppCallTracker.normalize(phone),
                path = file.absolutePath,
                endedAtMs = System.currentTimeMillis(),
                contactName = contactName,
                phone = phone,
                uploaded = false,
                startedAtMs = System.currentTimeMillis(),
                sourceLabel = sourceLabel,
                peakAmplitude = 1
            )
        )
    }

    fun forget(ctx: Context, path: String) = markUploaded(ctx, path)

    fun deleteRecording(ctx: Context, path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
        save(ctx, load(ctx).filter { it.path != path })
    }

    fun formatWhen(ms: Long): String {
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    fun formatSize(path: String): String {
        val n = File(path).length()
        return when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "${n / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
        }
    }

    private fun remember(ctx: Context, item: RecordingItem) {
        val list = load(ctx).toMutableList()
        list.add(0, item)
        while (list.size > MAX_KEEP) {
            val old = list.removeAt(list.lastIndex)
            try {
                File(old.path).delete()
            } catch (_: Exception) {
            }
        }
        save(ctx, list)
        if (autoSaveToDownloadsEnabled(ctx)) {
            val appCtx = ctx.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    exportToDownloads(appCtx, item)
                } catch (e: Exception) {
                    Log.w(TAG, "auto-export to Downloads failed: ${e.message}")
                }
            }
        }
    }

    private const val KEY_AUTO_SAVE_DOWNLOADS = "auto_save_downloads"

    /** Default ON — without this, saved recordings only live in app-private storage and
     * never show up in the phone's Files app, which is the whole point of recording calls. */
    fun autoSaveToDownloadsEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SAVE_DOWNLOADS, true)

    fun setAutoSaveToDownloads(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(RecordingConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SAVE_DOWNLOADS, enabled).apply()
    }

    /**
     * Copy a kept recording into the public Downloads/Syncpedia CRM folder so it shows up
     * in the device's own Files app, not just inside this app's private storage
     * (getExternalFilesDir, which most file managers hide/can't browse on Android 11+).
     * Android 10+ (Q): MediaStore insert, no extra permission needed.
     * Android 8–9: only if WRITE_EXTERNAL_STORAGE is already granted — this runs off a
     * background/service context with no Activity to prompt for it.
     */
    private fun exportToDownloads(ctx: Context, item: RecordingItem) {
        val src = File(item.path)
        if (!src.exists() || src.length() < RecordingConfig.MIN_FILE_SIZE) return

        val who = item.contactName.ifBlank { item.phone }.ifBlank { item.digits }.ifBlank { "Call" }
            .replace(Regex("[^A-Za-z0-9+_-]+"), "_").trim('_').take(40).ifBlank { "Call" }
        val ts = item.startedAtMs.takeIf { it > 0 } ?: item.endedAtMs
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))
        val ext = src.extension.ifBlank { "m4a" }
        val fileName = "${who}_$stamp.$ext"
        val mimeType = when (ext.lowercase(Locale.US)) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }
        val subDir = "Syncpedia CRM"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subDir")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Log.i(TAG, "Auto-saved recording to Downloads/$subDir/$fileName")
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
        } else {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Skipping auto-save to Downloads — storage permission not granted (Android < 10)")
                return
            }
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subDir)
            dir.mkdirs()
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mimeType), null)
            Log.i(TAG, "Auto-saved recording to Downloads/$subDir/$fileName")
        }
    }

    private fun load(ctx: Context): List<RecordingItem> {
        return try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_FILES, null) ?: return emptyList()
            val type = object : TypeToken<List<RecordingItem>>() {}.type
            gson.fromJson<List<RecordingItem>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<RecordingItem>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FILES, gson.toJson(list))
            .apply()
    }
}
