package com.syncpedia.crm.models

import com.google.gson.annotations.SerializedName

/**
 * Lead row from GET /api/leads.php { data: [...] }.
 */
data class Lead(
    val id: String = "",
    val name: String = "",
    val phone: String? = null,
    val status: String = "new",
    val email: String? = null,
    val source: String? = null,
    val notes: String? = null,
    val company: String? = null,
    @SerializedName("org_id")
    val orgId: String? = null,
    @SerializedName("assigned_to")
    val assignedTo: String? = null,
    @SerializedName("created_by")
    val createdBy: String? = null,
    @SerializedName("next_follow_up")
    val nextFollowUp: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null
)
