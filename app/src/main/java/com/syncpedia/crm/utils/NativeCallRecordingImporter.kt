package com.syncpedia.crm.utils

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File

/**
 * Picks up the recording made by the phone's OWN dialer (Xiaomi/Redmi/POCO, Samsung, Oppo,
 * Vivo, OnePlus, Realme… with auto call recording switched on) and hands it to the uploader.
 *
 * Third-party apps can't capture call audio on Android 10+, but the manufacturer dialer can,
 * and it saves an ordinary audio file to shared storage. After a call placed from Syncpedia
 * ends, we look in MediaStore for an audio file written around the end of that call, in a
 * call-recording folder or named after the number, copy it into the app and upload it.
 *
 * Not possible on phones that keep recordings private (e.g. Google Pixel's Phone app) or on
 * phones whose dialer has no recorder.
 */
object NativeCallRecordingImporter {
    private const val TAG = "NativeRecImport"

    /** Folder/name fragments used by manufacturer call recorders (lower-case). */
    private val CALL_FOLDER_HINTS = listOf(
        "call_rec", "callrec", "call rec", "/call", "call/", "callrecord", "call_record",
        "phonerecord", "phone record", "voice call", "sound_recorder/call", "recordings/call"
    )

    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            PermissionHelper.has(ctx, Manifest.permission.READ_MEDIA_AUDIO)
        else
            PermissionHelper.has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /**
     * Find the dialer's recording for a call to [phone] that ran from [callStartMs] to
     * [callEndMs]. Waits up to [waitMs] for the recorder to finish writing and MediaStore to
     * index the file. Returns a copy inside the app's recordings folder, or null.
     */
    suspend fun findForCall(
        ctx: Context,
        phone: String,
        callStartMs: Long,
        callEndMs: Long,
        waitMs: Long = 20_000L
    ): File? {
        if (!hasPermission(ctx)) return null
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val f = findOnce(ctx, phone, callStartMs, callEndMs)
            if (f != null) return f
            // Only worth waiting right after the call; old calls won't get a file later.
            if (System.currentTimeMillis() >= deadline ||
                System.currentTimeMillis() - callEndMs > 5 * 60_000L) return null
            delay(4_000L)
        }
    }

    private fun findOnce(ctx: Context, phone: String, callStartMs: Long, callEndMs: Long): File? {
        val digits = phone.filter { it.isDigit() }
        val last7 = if (digits.length >= 7) digits.takeLast(7) else ""
        val fromSec = (callStartMs - 60_000L) / 1000L
        val toSec = (callEndMs + 15 * 60_000L) / 1000L
        val callSec = ((callEndMs - callStartMs) / 1000L).coerceAtLeast(0L)

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
            @Suppress("DEPRECATION") add(MediaStore.Audio.Media.DATA)
        }.toTypedArray()
        val selection = "(${MediaStore.Audio.Media.DATE_ADDED} BETWEEN ? AND ?) OR " +
            "(${MediaStore.Audio.Media.DATE_MODIFIED} BETWEEN ? AND ?)"
        val args = arrayOf(fromSec.toString(), toSec.toString(), fromSec.toString(), toSec.toString())

        data class Cand(val id: Long, val name: String, val score: Long)
        val cands = mutableListOf<Cand>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iRel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
                @Suppress("DEPRECATION")
                val iData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val name = c.getString(iName).orEmpty()
                    val path = ((if (iRel >= 0) c.getString(iRel) else null).orEmpty() + "/" +
                        (if (iData >= 0) c.getString(iData) else null).orEmpty()).lowercase()
                    val nameDigits = name.filter { it.isDigit() }
                    val inCallFolder = CALL_FOLDER_HINTS.any { path.contains(it) }
                    val namedForNumber = last7.isNotEmpty() && nameDigits.contains(last7)
                    if (!inCallFolder && !namedForNumber) continue
                    if (c.getLong(iSize) < RecordingConfig.MIN_FILE_SIZE) continue
                    // Recording length should roughly match the call (allow ring time / rounding).
                    val durSec = c.getLong(iDur) / 1000L
                    if (callSec >= 15 && durSec > 0 && (durSec < callSec / 2 || durSec > callSec + 120)) continue
                    // Closest to the end of the call wins; a number match beats a folder match.
                    val written = maxOf(c.getLong(iAdded), c.getLong(iMod)) * 1000L
                    var score = kotlin.math.abs(written - callEndMs)
                    if (namedForNumber) score /= 4
                    cands += Cand(c.getLong(iId), name, score)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read audio: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed: ${e.message}")
            return null
        }
        val best = cands.minByOrNull { it.score } ?: return null
        return copyToApp(ctx, best.id, best.name)
    }

    private fun copyToApp(ctx: Context, id: Long, displayName: String): File? {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "call.m4a" }
        val target = File(CallRecorder.getRecordingsDir(ctx), "native_${id}_$safe")
        if (target.exists() && target.length() > 0) return target
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            if (target.exists() && target.length() > 0) target else null
        } catch (e: Exception) {
            Log.w(TAG, "Copy failed for $displayName: ${e.message}")
            try { target.delete() } catch (_: Exception) {}
            null
        }
    }
}
