package com.syncpedia.crm.models

data class LoginRequest(
    val email: String,
    val password: String,
    val action: String = "login"
)
