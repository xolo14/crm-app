package com.syncpedia.crm.utils

import java.io.File
import java.io.FileOutputStream

/**
 * MP3 encoding is disabled: the bundled LAME native library was not 16 KB page-size aligned,
 * which Google Play rejects for targetSdk 35+. Live recording uses MediaRecorder (m4a) instead.
 */
object Mp3Encoder {

    fun isAvailable(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun wavToMp3(wav: File, outMp3: File, bitrateKbps: Int = 64): File? = null

    /** Placeholder so the legacy capture path still compiles; never created while [isAvailable] is false. */
    class Stream(@Suppress("UNUSED_PARAMETER") sampleRate: Int, @Suppress("UNUSED_PARAMETER") bitrateKbps: Int = 64) {
        @Suppress("UNUSED_PARAMETER")
        fun encode(samples: ShortArray, count: Int, out: FileOutputStream) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun finish(out: FileOutputStream) = Unit
    }
}
