package com.syncpedia.crm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local CRM call row for sync. Paths only — recording files stay on disk.
 * Does NOT use device Call Log (Play policy); filled from Syncpedia dialer / app calls.
 */
@Entity(
    tableName = "call_records",
    indices = [Index(value = ["syncStatus"]), Index(value = ["deviceCallId"], unique = true)]
)
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Same id used in AppCallStore / device_call_id upload field. */
    val deviceCallId: Long,
    val employeeId: String?,
    val phoneNumber: String,
    val contactName: String,
    /** CallLog.Calls.* type ints. */
    val callType: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSeconds: Long,
    val recordingFilePath: String? = null,
    /** true when Device Owner — two-way capture more likely to work. */
    val recordingReliableExpected: Boolean = false,
    /** unavailable | ready | uploaded | none */
    val recordingState: String = "none",
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastError: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)
