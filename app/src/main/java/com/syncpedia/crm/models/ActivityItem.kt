package com.syncpedia.crm.models

import com.google.gson.annotations.SerializedName

data class ActivityItem(
    val id: String = "",
    val type: String? = null,
    val subject: String? = null,
    val description: String? = null,
    @SerializedName("lead_id")
    val leadId: String? = null,
    @SerializedName("occurred_at")
    val occurredAt: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null
)

data class CreateActivityRequest(
    val type: String,
    val subject: String,
    val description: String? = null,
    @SerializedName("lead_id")
    val leadId: String
)
