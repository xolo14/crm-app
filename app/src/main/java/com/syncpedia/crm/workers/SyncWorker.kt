package com.syncpedia.crm.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.syncpedia.crm.data.CallRecordRepository
import com.syncpedia.crm.utils.AppCallStore
import com.syncpedia.crm.utils.CallRecorder
import com.syncpedia.crm.utils.CallUploader
import com.syncpedia.crm.utils.LocationHelper
import com.syncpedia.crm.utils.RecordingConfig
import com.syncpedia.crm.utils.SessionManager
import com.syncpedia.crm.utils.UploadLock
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_MANUAL = "call_sync"
        private const val WORK_AUTO = "call_sync_auto"
        const val AUTO_SYNC_DELAY_SEC = 60L
        /** Skip large recording upload off Wi‑Fi when file exceeds this (bytes). */
        private const val LARGE_FILE_WIFI_ONLY = 2L * 1024L * 1024L

        fun schedule(ctx: Context, isManual: Boolean = true) {
            if (isManual) {
                WorkManager.getInstance(ctx).cancelUniqueWork(WORK_AUTO)
            }
            val data = workDataOf("manual" to isManual)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(
                    WORK_MANUAL,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        fun scheduleAfterCallEnded(ctx: Context, delaySeconds: Long = AUTO_SYNC_DELAY_SEC) {
            val data = workDataOf("manual" to false)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(
                    WORK_AUTO,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            Log.i(TAG, "Auto sync scheduled in ${delaySeconds}s (manual Sync if no internet)")
        }

        /** Enqueue the auto sync only if none is already pending/running (never cancels an in-flight upload). */
        fun ensureAutoSync(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("manual" to false))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_AUTO, ExistingWorkPolicy.KEEP, request)
        }

        fun hasInternet(ctx: Context): Boolean {
            return try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (_: Exception) {
                false
            }
        }

        fun scheduleWithRecording(
            ctx: Context,
            phoneNumber: String,
            contactName: String,
            callType: String,
            duration: Int,
            recordingPath: String?,
            lat: Double?,
            lng: Double?,
            deviceCallId: Long = 0L
        ) {
            scheduleUpload(
                ctx, phoneNumber, contactName, callType, duration,
                recordingPath, lat, lng, deviceCallId
            )
        }

        fun scheduleUpload(
            ctx: Context,
            phoneNumber: String,
            contactName: String,
            callType: String,
            duration: Int,
            recordingPath: String?,
            lat: Double?,
            lng: Double?,
            deviceCallId: Long = 0L,
            delaySeconds: Long = 0L,
            calledAtMs: Long = 0L
        ) {
            val data = workDataOf(
                "mode" to "direct",
                "phone" to phoneNumber,
                "name" to contactName,
                "type" to callType,
                "duration" to duration,
                "path" to recordingPath,
                "hasLatLng" to (lat != null),
                "lat" to (lat ?: 0.0),
                "lng" to (lng ?: 0.0),
                "deviceCallId" to deviceCallId,
                "calledAtMs" to calledAtMs
            )
            val uniqueName =
                "upload_${System.currentTimeMillis()}_${phoneNumber.takeLast(4).ifBlank { "x" }}"
            val builder = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            if (delaySeconds > 0L) {
                builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            }
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
            Log.i(
                TAG,
                "Upload scheduled: $phoneNumber ($callType, ${duration}s)" +
                    if (delaySeconds > 0) " in ${delaySeconds}s" else ""
            )
        }
    }

    override suspend fun doWork(): Result {
        val session = SessionManager.getInstance(applicationContext)
        if (!session.isLoggedIn()) return Result.success()

        val isManual = inputData.getBoolean("manual", false)
        if (!hasInternet(applicationContext)) {
            val reason = "No internet — tap Sync when online"
            if (!isManual) {
                SyncPrefs.setNeedsManual(applicationContext, reason)
                Log.w(TAG, "Auto sync skipped, left for manual: $reason")
            }
            return Result.failure()
        }

        val repo = CallRecordRepository(applicationContext)
        val mode = inputData.getString("mode")
        val phoneNumber = inputData.getString("phone")
        val contactName = inputData.getString("name") ?: "Unknown"
        val callType = inputData.getString("type") ?: "completed"
        val duration = inputData.getInt("duration", 0)
        val recordingPath = inputData.getString("path")
        val hasLatLng = inputData.getBoolean("hasLatLng", false)
        val lat = if (hasLatLng) inputData.getDouble("lat", 0.0) else null
        val lng = if (hasLatLng) inputData.getDouble("lng", 0.0) else null
        val deviceCallId = inputData.getLong("deviceCallId", 0L)
        val calledAtMs = inputData.getLong("calledAtMs", 0L)

        if (mode == "direct" || !phoneNumber.isNullOrBlank() || !recordingPath.isNullOrBlank()) {
            if (deviceCallId > 0) repo.markUploading(deviceCallId)
            val file = recordingPath?.let { File(it) }
                ?.takeIf { it.exists() && it.length() >= RecordingConfig.MIN_FILE_SIZE }
            val phone = phoneNumber.orEmpty().ifBlank { "unknown" }
            val uploader = CallUploader(applicationContext)
            val ok = uploader.uploadDirect(
                phoneNumber = phone,
                contactName = contactName,
                callType = callType,
                duration = duration,
                recordingFile = file,
                latitude = lat,
                longitude = lng,
                deviceCallId = if (deviceCallId > 0) deviceCallId.toString()
                else "direct-${phone.takeLast(8)}-$duration-${System.currentTimeMillis()}",
                calledAtMs = calledAtMs
            )
            return when {
                ok -> {
                    if (deviceCallId > 0) {
                        repo.markSynced(deviceCallId, deleteRecording = SyncPrefs.deleteAfterSync(applicationContext))
                    }
                    stampLastSync()
                    Result.success()
                }
                runAttemptCount < 3 -> {
                    if (deviceCallId > 0) repo.markFailed(deviceCallId, "upload retry")
                    Result.retry()
                }
                else -> {
                    if (deviceCallId > 0) repo.markFailed(deviceCallId, "upload failed")
                    Result.failure()
                }
            }
        }

        return UploadLock.mutex.withLock { bulkSync(session, isManual, repo) }
    }

    private suspend fun bulkSync(
        session: SessionManager,
        isManual: Boolean,
        repo: CallRecordRepository
    ): Result {
        // Merge Room pending with local unsynced so a just-ended call is included
        // even if the Room write has not finished yet.
        val roomPending = repo.pendingForSync(100)
        val roomById = roomPending.associate { it.deviceCallId to it.recordingFilePath }
        val localUnsynced = AppCallStore.getUnsynced(applicationContext) { id -> session.isSynced(id) }
        val merged = linkedMapOf<Long, Pair<com.syncpedia.crm.models.CallLogItem, String?>>()
        for (row in roomPending) {
            merged[row.deviceCallId] = repo.toCallLogItem(row) to row.recordingFilePath
        }
        for (item in localUnsynced) {
            if (item.id !in merged) {
                merged[item.id] = item to roomById[item.id]
            }
        }
        val toUpload = merged.values.toList()
        if (toUpload.isEmpty()) return Result.success()

        var bulkLat: Double? = null
        var bulkLng: Double? = null
        val latch = CountDownLatch(1)
        LocationHelper(applicationContext).get(
            onSuccess = { la, lo ->
                bulkLat = la
                bulkLng = lo
                latch.countDown()
            },
            onFail = { latch.countDown() }
        )
        latch.await(5, TimeUnit.SECONDS)

        val uploader = CallUploader(applicationContext)
        var allOk = true
        var deferredAny = false
        val onWifi = isWifi()
        val knownPaths = roomPending.mapNotNull { it.recordingFilePath }.toMutableSet()
        for ((call, pathHint) in toUpload) {
            val path = pathHint ?: CallRecorder.findForCall(applicationContext, call)?.absolutePath
            path?.let { knownPaths.add(it) }
            val file = path?.let { File(it) }
            val deferWifi = !isManual && file != null && file.length() > LARGE_FILE_WIFI_ONLY && !onWifi
            if (deferWifi) {
                // Leave the whole call pending so it uploads once, with its recording, on Wi‑Fi / manual Sync.
                Log.i(TAG, "Defer call with large recording until Wi‑Fi: ${file?.name}")
                deferredAny = true
                continue
            }
            repo.markUploading(call.id)
            val uploadPath = path
            val ok = uploader.upload(call, bulkLat, bulkLng, recordingPath = uploadPath)
            if (ok) {
                repo.markSynced(call.id, deleteRecording = SyncPrefs.deleteAfterSync(applicationContext))
            } else {
                allOk = false
                repo.markFailed(call.id, "bulk upload failed")
            }
        }

        // Also ensure any un-uploaded recordings in CallRecorder are sent directly to website
        try {
            val unUploadedRecs = CallRecorder.listAll(applicationContext).filter { !it.uploaded }
            for (rec in unUploadedRecs) {
                val f = File(rec.path)
                val belongsToCall = rec.path in knownPaths
                val deferThis = !isManual && f.length() > LARGE_FILE_WIFI_ONLY && !onWifi
                if (!belongsToCall && !deferThis && f.exists() && f.length() >= RecordingConfig.MIN_FILE_SIZE) {
                    val phone = rec.phone.ifBlank { rec.digits }.ifBlank { "unknown" }
                    val directOk = uploader.uploadDirect(
                        phoneNumber = phone,
                        contactName = rec.contactName.ifBlank { "Recording" },
                        callType = "completed",
                        duration = (f.length() / 8000L).toInt().coerceAtLeast(1),
                        recordingFile = f,
                        latitude = bulkLat,
                        longitude = bulkLng,
                        deviceCallId = "rec-${f.name}".take(64)
                    )
                    if (directOk) {
                        CallRecorder.markUploaded(applicationContext, rec.path)
                        if (SyncPrefs.deleteAfterSync(applicationContext)) {
                            try { f.delete() } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Standalone recording sync exception: ${e.message}")
        }

        return when {
            allOk -> {
                stampLastSync()
                SyncPrefs.setNeedsManual(
                    applicationContext,
                    if (deferredAny) "Large recording waiting for Wi‑Fi — tap Sync to upload now" else null
                )
                Result.success()
            }
            isManual && runAttemptCount < 3 -> Result.retry()
            else -> {
                SyncPrefs.setNeedsManual(applicationContext, "Upload failed — tap Sync when online")
                Result.failure()
            }
        }
    }

    private fun stampLastSync() {
        applicationContext.getSharedPreferences("sp_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_ts", System.currentTimeMillis()).apply()
    }

    private fun isWifi(): Boolean {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }
}

object SyncPrefs {
    private const val PREFS = "sp_sync"
    private const val KEY_DELETE = "delete_recording_after_sync"
    private const val KEY_NEEDS_MANUAL = "needs_manual"

    fun deleteAfterSync(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DELETE, false)

    fun setDeleteAfterSync(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DELETE, value).apply()
    }

    fun needsManual(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NEEDS_MANUAL, null)
            ?.takeIf { it.isNotBlank() }

    fun setNeedsManual(ctx: Context, reason: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NEEDS_MANUAL, reason)
            .apply()
    }
}
