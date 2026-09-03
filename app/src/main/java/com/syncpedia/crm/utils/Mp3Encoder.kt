package com.syncpedia.crm.utils

import android.util.Log
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Encodes 16-bit mono PCM to MP3 via LAME.
 */
object Mp3Encoder {

    private const val TAG = "Mp3Encoder"

    fun isAvailable(): Boolean = try {
        LameBuilder()
        true
    } catch (t: Throwable) {
        Log.e(TAG, "LAME unavailable: ${t.message}")
        false
    }

    private fun buildLame(sampleRate: Int, bitrateKbps: Int): AndroidLame =
        LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutChannels(1)
            .setOutBitrate(bitrateKbps)
            .setOutSampleRate(sampleRate)
            .setMode(LameBuilder.Mode.MONO)
            .setQuality(5)
            .build()

    /**
     * Convert a 16-bit mono WAV to MP3. Returns mp3 file or null.
     */
    fun wavToMp3(wav: File, outMp3: File, bitrateKbps: Int = 64): File? {
        if (!wav.exists() || wav.length() < 100L) return null
        return try {
            val raf = RandomAccessFile(wav, "r")
            raf.seek(24)
            val rateBytes = ByteArray(4)
            raf.readFully(rateBytes)
            val sampleRate = (rateBytes[0].toInt() and 0xff) or
                ((rateBytes[1].toInt() and 0xff) shl 8) or
                ((rateBytes[2].toInt() and 0xff) shl 16) or
                ((rateBytes[3].toInt() and 0xff) shl 24)
            raf.seek(44)
            val pcmLen = (raf.length() - 44).toInt().coerceAtLeast(0)
            val pcm = ByteArray(pcmLen)
            raf.readFully(pcm)
            raf.close()

            val rate = if (sampleRate in 8000..48000) sampleRate else 16000
            val lame = buildLame(rate, bitrateKbps)

            FileOutputStream(outMp3).use { out ->
                val shortBuf = ShortArray(1152)
                val mp3buf = ByteArray((1.25 * shortBuf.size + 7200).toInt())
                var offset = 0
                while (offset + 1 < pcm.size) {
                    var n = 0
                    while (n < shortBuf.size && offset + 1 < pcm.size) {
                        val s = (pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)
                        shortBuf[n++] = s.toShort()
                        offset += 2
                    }
                    if (n <= 0) break
                    val encoded = lame.encode(shortBuf, shortBuf, n, mp3buf)
                    if (encoded > 0) out.write(mp3buf, 0, encoded)
                }
                val flushed = lame.flush(mp3buf)
                if (flushed > 0) out.write(mp3buf, 0, flushed)
            }
            try {
                lame.close()
            } catch (_: Exception) {
            }
            if (outMp3.exists() && outMp3.length() > 500L) outMp3 else {
                outMp3.delete()
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wavToMp3 failed: ${t.message}")
            try {
                outMp3.delete()
            } catch (_: Exception) {
            }
            null
        }
    }

    /** Live streaming encoder for call capture. */
    class Stream(sampleRate: Int, bitrateKbps: Int = 64) {
        private val lame: AndroidLame = buildLame(sampleRate, bitrateKbps)
        private val mp3buf = ByteArray((1.25 * 8192 + 7200).toInt())
        private val mixBuf = ShortArray(8192)

        fun encode(samples: ShortArray, count: Int, out: FileOutputStream) {
            if (count <= 0) return
            var offset = 0
            while (offset < count) {
                val n = minOf(mixBuf.size, count - offset)
                System.arraycopy(samples, offset, mixBuf, 0, n)
                val encoded = lame.encode(mixBuf, mixBuf, n, mp3buf)
                if (encoded > 0) out.write(mp3buf, 0, encoded)
                offset += n
            }
        }

        fun finish(out: FileOutputStream) {
            val flushed = lame.flush(mp3buf)
            if (flushed > 0) out.write(mp3buf, 0, flushed)
            try {
                lame.close()
            } catch (_: Exception) {
            }
        }
    }
}
