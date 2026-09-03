package com.syncpedia.crm.models

data class CallLogItem(
    val id: Long,
    val contactName: String,
    val phoneNumber: String,
    val callType: Int,
    val date: Long,
    val duration: Long,
    val isSynced: Boolean = false
)
