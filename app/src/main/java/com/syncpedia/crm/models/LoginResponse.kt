package com.syncpedia.crm.models

import com.google.gson.annotations.SerializedName

/** Nested organization object from POST /api/auth.php?action=login */
data class OrganizationInfo(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null
)

data class LoginResponse(
    val token: String? = null,
    val user: UserInfo? = null,
    val organization: OrganizationInfo? = null,
    val error: String? = null,
    val message: String? = null
)
