package com.syncpedia.crm.utils

import android.content.Context
import com.syncpedia.crm.data.CallRecordRepository
import com.syncpedia.crm.models.CallLogItem
import kotlinx.coroutines.sync.withLock

/** Uploads waiting calls immediately in this process, not via WorkManager. */
/** Serializes every bulk upload path so two syncs never upload the same call at once. */
object UploadLock {
    val mutex = kotlinx.coroutines.sync.Mutex()
}

object PendingCallSync {

    suspend fun uploadAll(ctx: Context): String = UploadLock.mutex.withLock { uploadAllLocked(ctx) }

    private suspend fun uploadAllLocked(ctx: Context): String {
        val app = ctx.applicationContext
        val session = SessionManager.getInstance(app)
        if (!session.isLoggedIn()) return "Not logged in — open the app and sign in again"

        val repo = CallRecordRepository(app)
        val room = repo.pendingForSync(100)
        val roomPaths = room.associate { it.deviceCallId to it.recordingFilePath }
        val local = AppCallStore.getUnsynced(app) { id -> session.isSynced(id) }
        val merged = linkedMapOf<Long, Pair<CallLogItem, String?>>()
        for (row in room) {
            merged[row.deviceCallId] = repo.toCallLogItem(row) to row.recordingFilePath
        }
        for (item in local) {
            if (item.id !in merged) merged[item.id] = item to roomPaths[item.id]
        }
        if (merged.isEmpty()) return "No saved calls waiting to upload"

        val uploader = CallUploader(app)
        var sent = 0
        var lastFail: String? = null
        for ((_, pair) in merged) {
            val (item, path) = pair
            val ok = uploader.upload(item, null, null, path)
            if (ok) {
                sent++
                repo.markSynced(item.id, deleteRecording = false)
            } else {
                lastFail = uploader.lastError
                repo.markFailed(item.id, lastFail)
            }
        }
        return if (sent == merged.size) {
            "Uploaded $sent call(s) to the website"
        } else if (sent > 0) {
            "Uploaded $sent, failed ${merged.size - sent}: ${lastFail ?: "website rejected"}"
        } else {
            lastFail ?: "Website did not accept the call"
        }
    }
}
