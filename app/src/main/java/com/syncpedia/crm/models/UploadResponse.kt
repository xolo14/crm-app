package com.syncpedia.crm.models

data class UploadResponse(
    val success: Boolean? = null,
    val callId: Int? = null,
    val id: Int? = null,
    val error: String? = null,
    val message: String? = null
)
