package com.syncpedia.crm.utils

import android.content.Context
import android.media.MediaRecorder

/**
 * Central config for multi-layer call recording.
 * Never use VOICE_COMMUNICATION — broken/restricted on Android 10+.
 */
object RecordingConfig {

    /** Prefer true call-mix first; then air-capture sources with speaker. */
    val SOURCE_PRIORITY = listOf(
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_DOWNLINK,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.MIC
    )

    /** Sources that need loudspeaker “air capture” (no telephony mix). */
    val AIR_CAPTURE_SOURCES = setOf(
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION
    )

    const val PREFS_NAME = "sp_recording_config"
    const val KEY_WORKING_SOURCE = "working_audio_source"
    const val KEY_USE_SPEAKER_MODE = "use_speaker_mode"
    /** Configurable silence peak (MediaRecorder.maxAmplitude). Default 50 — was 200 (too aggressive). */
    const val KEY_SILENCE_PEAK = "silence_peak_threshold"
    const val DEFAULT_SILENCE_PEAK = 50
    /** MIC air-capture is quieter; use a lower bar so real quiet speech is kept. */
    const val DEFAULT_MIC_SILENCE_PEAK = 15
    const val KEY_DISCARD_LOG = "discard_log"

    const val SAMPLE_RATE = 16_000
    /** MP3 bitrate in bits/sec (LAME uses kbps = BIT_RATE / 1000). */
    const val BIT_RATE = 64_000
    const val MIN_FILE_SIZE = 2048L
    /** Keep takes at/above this size even if MediaRecorder reports peak=0. */
    const val MIN_KEEP_BYTES = 12_000L
    const val MAX_RECORDING_MS = 3_600_000L

    fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL (both sides)"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK (remote)"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION + Speaker"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER + Speaker"
        MediaRecorder.AudioSource.MIC -> "MIC + Speaker (air capture)"
        else -> "UNKNOWN($source)"
    }

    fun shortSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.MIC -> "MIC+Speaker"
        else -> "SRC($source)"
    }

    fun isAirCapture(source: Int): Boolean = source in AIR_CAPTURE_SOURCES

    fun silencePeakThreshold(ctx: Context, audioSource: Int = -1): Int {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = prefs.getInt(KEY_SILENCE_PEAK, DEFAULT_SILENCE_PEAK)
            .coerceIn(10, 500)
        return if (isAirCapture(audioSource)) {
            minOf(base, DEFAULT_MIC_SILENCE_PEAK).coerceAtLeast(8)
        } else {
            base
        }
    }

    fun setSilencePeakThreshold(ctx: Context, value: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SILENCE_PEAK, value.coerceIn(10, 500))
            .apply()
    }

    fun workingSourceLabel(ctx: Context): String {
        val src = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WORKING_SOURCE, -1)
        return if (src >= 0) sourceName(src) else "Auto-detect on next call"
    }

    fun appendDiscardLog(ctx: Context, line: String) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_DISCARD_LOG, "").orEmpty()
        val next = (line + "\n" + prev).take(4000)
        prefs.edit().putString(KEY_DISCARD_LOG, next).apply()
    }

    fun discardLog(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISCARD_LOG, "")
            .orEmpty()
}
