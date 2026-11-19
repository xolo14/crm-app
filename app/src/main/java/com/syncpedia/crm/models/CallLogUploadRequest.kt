package com.syncpedia.crm.models

import com.google.gson.annotations.SerializedName

/**
 * Body for POST /api/call_logs.php?action=add_log — the endpoint the website Call Log uses.
 */
data class CallLogUploadRequest(
    @SerializedName("call_type") val callType: String,
    @SerializedName("call_status") val callStatus: String,
    @SerializedName("duration_seconds") val durationSeconds: Int,
    @SerializedName("call_date") val callDate: String,
    @SerializedName("call_time") val callTime: String,
    @SerializedName("client_phone") val clientPhone: String,
    @SerializedName("client_name") val clientName: String,
    @SerializedName("device_call_id") val deviceCallId: String,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("lead_id") val leadId: String? = null,
    /** "no_audio" when the phone recorded the call but captured only silence. */
    @SerializedName("recording_status") val recordingStatus: String? = null
)
