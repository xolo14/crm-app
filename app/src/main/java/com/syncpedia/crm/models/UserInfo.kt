package com.syncpedia.crm.models

import com.google.gson.annotations.SerializedName

/**
 * Live auth.php returns the users row (id, email, full_name, role, org_id, …).
 */
data class UserInfo(
    val id: String = "",
    val email: String = "",
    val role: String = "",
    @SerializedName("full_name")
    val fullName: String? = null,
    val name: String? = null,
    @SerializedName("org_id")
    val orgId: String? = null
) {
    fun displayName(): String {
        val n = name?.trim().orEmpty()
        if (n.isNotEmpty()) return n
        val f = fullName?.trim().orEmpty()
        if (f.isNotEmpty()) return f
        return email.substringBefore("@").ifBlank { "User" }
    }

    fun normalizedRole(): String = role.trim().lowercase().replace(' ', '_')
}
