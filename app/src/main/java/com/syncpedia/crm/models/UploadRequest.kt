package com.syncpedia.crm.models

/**
 * Multipart field model for POST /api/calls.php (documentation / local use).
 * Actual upload goes through ApiService.uploadCall() form parts.
 */
data class UploadRequest(
    val leadId: String?,
    val phoneNumber: String,
    val contactName: String,
    val callType: String,
    val duration: Int,
    val latitude: Double?,
    val longitude: Double?,
    val calledAt: String,
    val deviceCallId: String
)
