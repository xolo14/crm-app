package com.syncpedia.crm.models

/**
 * Envelope from GET /api/leads.php and GET /api/calls.php:
 * { "data": [...], "count", "total", "limit", "offset", "truncated" }
 */
data class ApiListResponse<T>(
    val data: List<T> = emptyList(),
    val count: Int? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val truncated: Boolean? = null,
    val error: String? = null
)
