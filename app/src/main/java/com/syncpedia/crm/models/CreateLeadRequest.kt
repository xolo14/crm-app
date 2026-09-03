package com.syncpedia.crm.models

data class CreateLeadRequest(
    val name: String,
    val phone: String,
    val email: String? = null,
    val notes: String? = null,
    val status: String = "new",
    val source: String = "other"
)
