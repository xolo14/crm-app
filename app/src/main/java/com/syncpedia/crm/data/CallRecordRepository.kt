package com.syncpedia.crm.data

import android.content.Context
import android.provider.CallLog
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.utils.DeviceOwnerHelper
import com.syncpedia.crm.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CallRecordRepository(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val dao = AppDatabase.get(appCtx).callRecordDao()

    suspend fun upsertFromLocalCall(
        item: CallLogItem,
        endTimeMs: Long = item.date + item.duration * 1000L,
        recordingPath: String? = null
    ) = withContext(Dispatchers.IO) {
        val existing = dao.findByDeviceCallId(item.id)
        val reliable = DeviceOwnerHelper.isRecordingExpectedReliable(appCtx)
        val recState = when {
            recordingPath.isNullOrBlank() -> existing?.recordingState ?: "none"
            isValidRecording(recordingPath) -> "ready"
            else -> "unavailable"
        }
        val path = if (recState == "ready") recordingPath else existing?.recordingFilePath
        val employeeId = SessionManager.getInstance(appCtx).getUser()?.id
        val row = CallRecordEntity(
            id = existing?.id ?: 0,
            deviceCallId = item.id,
            employeeId = employeeId,
            phoneNumber = item.phoneNumber,
            contactName = item.contactName,
            callType = item.callType,
            startTimeMs = item.date,
            endTimeMs = endTimeMs,
            durationSeconds = item.duration,
            recordingFilePath = path,
            recordingReliableExpected = reliable,
            recordingState = recState,
            syncStatus = if (item.isSynced) SyncStatus.SYNCED else SyncStatus.PENDING,
            lastError = existing?.lastError,
            updatedAtMs = System.currentTimeMillis()
        )
        dao.upsert(row)
    }

    suspend fun attachRecording(deviceCallId: Long, path: String?) = withContext(Dispatchers.IO) {
        val state = when {
            path.isNullOrBlank() -> "none"
            isValidRecording(path) -> "ready"
            else -> "unavailable"
        }
        dao.updateRecording(deviceCallId, if (state == "ready") path else null, state)
    }

    suspend fun pendingForSync(limit: Int = 100): List<CallRecordEntity> = withContext(Dispatchers.IO) {
        dao.listByStatuses(listOf(SyncStatus.PENDING, SyncStatus.FAILED), limit)
    }

    suspend fun markUploading(deviceCallId: Long) = withContext(Dispatchers.IO) {
        dao.updateSyncStatus(deviceCallId, SyncStatus.UPLOADING, null)
    }

    suspend fun markSynced(deviceCallId: Long, deleteRecording: Boolean = true) =
        withContext(Dispatchers.IO) {
            val row = dao.findByDeviceCallId(deviceCallId)
            if (deleteRecording) {
                row?.recordingFilePath?.let { p ->
                    try {
                        File(p).delete()
                    } catch (_: Exception) {
                    }
                }
                dao.updateRecording(deviceCallId, null, "uploaded")
            }
            dao.updateSyncStatus(deviceCallId, SyncStatus.SYNCED, null)
        }

    suspend fun markFailed(deviceCallId: Long, error: String?) = withContext(Dispatchers.IO) {
        dao.updateSyncStatus(deviceCallId, SyncStatus.FAILED, error?.take(200))
    }

    suspend fun syncSummary(): SyncSummary = withContext(Dispatchers.IO) {
        val pending = dao.countByStatus(SyncStatus.PENDING)
        val uploading = dao.countByStatus(SyncStatus.UPLOADING)
        val failed = dao.countByStatus(SyncStatus.FAILED)
        val synced = dao.countByStatus(SyncStatus.SYNCED)
        val ready = dao.listByStatuses(listOf(SyncStatus.PENDING, SyncStatus.FAILED), 500)
            .mapNotNull { it.recordingFilePath }
            .map { File(it) }
            .filter { it.exists() }
        val bytes = ready.sumOf { it.length() }
        SyncSummary(pending, uploading, failed, synced, bytes, ready.size)
    }

    fun toCallLogItem(row: CallRecordEntity): CallLogItem = CallLogItem(
        id = row.deviceCallId,
        contactName = row.contactName,
        phoneNumber = row.phoneNumber,
        callType = row.callType,
        date = row.startTimeMs,
        duration = row.durationSeconds,
        isSynced = row.syncStatus == SyncStatus.SYNCED
    )

    private fun isValidRecording(path: String): Boolean {
        val f = File(path)
        return f.exists() && f.length() > 2048L
    }

    data class SyncSummary(
        val pending: Int,
        val uploading: Int,
        val failed: Int,
        val synced: Int,
        val pendingRecordingBytes: Long,
        val pendingRecordingFiles: Int
    ) {
        fun storageLabel(): String {
            if (pendingRecordingBytes <= 0L) return "0 KB pending"
            val kb = pendingRecordingBytes / 1024.0
            return if (kb < 1024) String.format("%.0f KB pending", kb)
            else String.format("%.1f MB pending", kb / 1024.0)
        }
    }

    companion object {
        fun typeLabel(callType: Int): String = when (callType) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.REJECTED_TYPE -> "rejected"
            else -> "outgoing"
        }
    }
}
