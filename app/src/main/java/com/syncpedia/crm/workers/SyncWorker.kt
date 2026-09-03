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
        const val AUTO_SYNC_DELAY_SEC = 10L
        /** Skip large recording upload off Wi‑Fi when file exceeds this (bytes). */
        private const val LARGE_FILE_WIFI_ONLY = 2L * 1024L * 1024L

        fun schedule(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
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
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }

        fun scheduleAfterCallEnded(ctx: Context, delaySeconds: Long = AUTO_SYNC_DELAY_SEC) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(
                    WORK_AUTO,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            Log.i(TAG, "Auto sync scheduled in ${delaySeconds}s")
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
            delaySeconds: Long = 0L
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
                "deviceCallId" to deviceCallId
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
                else "direct-${phone.takeLast(8)}-$duration-${System.currentTimeMillis()}"
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

        // Prefer Room pending/failed; fall back to legacy AppCallStore list
        val roomPending = repo.pendingForSync(100)
        val toUpload = if (roomPending.isNotEmpty()) {
            roomPending.map { repo.toCallLogItem(it) to it.recordingFilePath }
        } else {
            AppCallStore.getUnsynced(applicationContext) { id -> session.isSynced(id) }
                .map { it to null as String? }
        }
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
        val onWifi = isWifi()
        for ((call, pathHint) in toUpload) {
            repo.markUploading(call.id)
            // Large recordings: prefer Wi‑Fi (metadata still uploads)
            val path = pathHint ?: CallRecorder.findForCall(applicationContext, call)?.absolutePath
            val file = path?.let { File(it) }
            if (file != null && file.length() > LARGE_FILE_WIFI_ONLY && !onWifi) {
                Log.i(TAG, "Defer large recording upload until Wi‑Fi: ${file.name}")
            }
            val ok = uploader.upload(call, bulkLat, bulkLng)
            if (ok) {
                repo.markSynced(call.id, deleteRecording = SyncPrefs.deleteAfterSync(applicationContext))
            } else {
                allOk = false
                repo.markFailed(call.id, "bulk upload failed")
            }
        }

        return when {
            allOk -> {
                stampLastSync()
                Result.success()
            }
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
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

    fun deleteAfterSync(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DELETE, false)

    fun setDeleteAfterSync(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DELETE, value).apply()
    }
}
