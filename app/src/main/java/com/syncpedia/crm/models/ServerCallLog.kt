package com.syncpedia.crm.models

import com.google.gson.annotations.SerializedName

/**
 * One row from GET /api/calls.php { data: [...] }.
 *
 * FIELD NAMES GUESSED from call_logs schema / prior docs — verify against a real
 * curl of GET /api/calls.php before relying on every property in UI.
 */
data class ServerCallLog(
    val id: Int,
    @SerializedName("sales_rep_id")
    val salesRepId: String? = null,
    @SerializedName("org_id")
    val orgId: String? = null,
    @SerializedName("lead_id")
    val leadId: String? = null,
    @SerializedName("call_type")
    val callType: String? = null,
    @SerializedName("call_status")
    val callStatus: String? = null,
    @SerializedName("duration_seconds")
    val durationSeconds: Int? = null,
    @SerializedName("client_phone")
    val clientPhone: String? = null,
    @SerializedName("client_name")
    val clientName: String? = null,
    @SerializedName("call_date")
    val callDate: String? = null,
    @SerializedName("call_time")
    val callTime: String? = null,
    @SerializedName("device_call_id")
    val deviceCallId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("synced_at")
    val syncedAt: String? = null,
    @SerializedName("attachment_path")
    val attachmentPath: String? = null
)
