package com.syncpedia.crm.models

/**
 * Response for POST /api/leads.php:
 * { "id": "<uuid>", "message": "Lead created" }
 */
data class CreateLeadResponse(
    val id: String,
    val message: String? = null
)
