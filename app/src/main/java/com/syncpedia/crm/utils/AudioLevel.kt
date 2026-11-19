package com.syncpedia.crm.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * Measures how loud a recording really is by decoding it to 16-bit PCM.
 *
 * File size says nothing about sound: on most Android 10+ phones a third-party app can't
 * capture call audio, so the recorder writes a perfectly valid file of pure silence (a 2-minute
 * AAC file of silence is still ~1 MB). Those used to upload with a play button.
 */
object AudioLevel {
    private const val TAG = "AudioLevel"

    /** Peak at or below this (of 32767) is treated as silence (~ -54 dBFS). */
    const val SILENT_PEAK = 64

    /**
     * Highest absolute 16-bit sample in the first [maxSeconds] of [file], or null when the file
     * can't be decoded (unknown codec, corrupt file) — callers then keep their old behaviour.
     * Returns early as soon as a sample above [SILENT_PEAK] is found.
     */
    fun peak(file: File, maxSeconds: Int = 600): Int? {
        if (!file.exists() || file.length() < 1024) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val limitUs = maxSeconds * 1_000_000L
            var inputDone = false
            var peak = 0
            var idleLoops = 0
            while (idleLoops < 200) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0 || extractor.sampleTime > limitUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    idleLoops = 0
                    val out = codec.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shorts.hasRemaining()) {
                            val s = shorts.get().toInt()
                            val a = if (s < 0) -s else s
                            if (a > peak) {
                                peak = a
                                if (peak > SILENT_PEAK) {
                                    codec.releaseOutputBuffer(outIdx, false)
                                    return peak
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else {
                    idleLoops++
                }
            }
            return peak
        } catch (e: Exception) {
            Log.w(TAG, "peak() failed for ${file.name}: ${e.message}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** true = silent, false = has sound, null = couldn't tell. */
    fun isSilent(file: File): Boolean? = peak(file)?.let { it <= SILENT_PEAK }
}
