package com.syncpedia.crm.models

data class UpdateLeadRequest(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,
    val status: String? = null
)
