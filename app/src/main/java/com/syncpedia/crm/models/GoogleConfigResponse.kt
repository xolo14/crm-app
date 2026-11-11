package com.syncpedia.crm.models

import com.google.gson.annotations.SerializedName

data class GoogleConfigResponse(
    @SerializedName("client_id") val clientId: String? = null,
    val enabled: Boolean? = null
)
