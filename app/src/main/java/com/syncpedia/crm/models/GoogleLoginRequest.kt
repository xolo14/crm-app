package com.syncpedia.crm.models

data class GoogleLoginRequest(
    val credential: String,
    val action: String = "google_login"
)
