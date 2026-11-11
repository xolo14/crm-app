package com.syncpedia.crm.models

/** GET /api/call_logs.php?action=get_logs */
data class CallLogsPageResponse(
    val logs: List<ServerCallLog> = emptyList(),
    val total: Int? = null,
    val error: String? = null
)
