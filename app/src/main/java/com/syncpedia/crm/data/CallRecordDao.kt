package com.syncpedia.crm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CallRecordEntity): Long

    @Update
    suspend fun update(row: CallRecordEntity)

    @Query("SELECT * FROM call_records WHERE deviceCallId = :deviceCallId LIMIT 1")
    suspend fun findByDeviceCallId(deviceCallId: Long): CallRecordEntity?

    @Query(
        "SELECT * FROM call_records WHERE syncStatus IN (:statuses) ORDER BY startTimeMs DESC LIMIT :limit"
    )
    suspend fun listByStatuses(statuses: List<SyncStatus>, limit: Int = 200): List<CallRecordEntity>

    @Query("SELECT COUNT(*) FROM call_records WHERE syncStatus = :status")
    suspend fun countByStatus(status: SyncStatus): Int

    @Query("SELECT * FROM call_records ORDER BY startTimeMs DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 300): List<CallRecordEntity>

    @Query(
        "UPDATE call_records SET syncStatus = :status, lastError = :error, updatedAtMs = :now WHERE deviceCallId = :deviceCallId"
    )
    suspend fun updateSyncStatus(
        deviceCallId: Long,
        status: SyncStatus,
        error: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE call_records SET syncStatus = 'PENDING', updatedAtMs = :now WHERE syncStatus = 'SYNCED'"
    )
    suspend fun resetSyncedToPending(now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE call_records SET recordingFilePath = :path, recordingState = :state, updatedAtMs = :now WHERE deviceCallId = :deviceCallId"
    )
    suspend fun updateRecording(
        deviceCallId: Long,
        path: String?,
        state: String,
        now: Long = System.currentTimeMillis()
    )
}
