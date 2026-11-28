package com.syncpedia.crm.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.syncpedia.crm.models.UserInfo

/**
 * Session + tenant helpers matching crm-main PHP access rules.
 * Token/user live in EncryptedSharedPreferences.
 */
class SessionManager private constructor(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val gson = Gson()
    private val prefs: SharedPreferences = createSecurePrefs(appCtx)

    /** Login: store the token and count signing in as activity. */
    fun saveToken(t: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_TOKEN, t)
            .putLong(KEY_TOKEN_TS, now)
            .putLong(KEY_LAST_ACTIVE, now)
            .apply()
        // Prefer org_id from JWT claims when login payload omits it
        extractClaim(t, "org_id")?.let { org ->
            if (getOrgId().isNullOrBlank()) {
                prefs.edit().putString(KEY_ORG_ID, org).apply()
            }
        }
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Server re-issued the token (X-Refreshed-Token). Not user activity — background syncs renew too. */
    fun renewToken(t: String) {
        prefs.edit()
            .putString(KEY_TOKEN, t)
            .putLong(KEY_TOKEN_TS, System.currentTimeMillis())
            .apply()
    }

    /**
     * The rep actually used the app (opened a screen or made a call). Checks the inactivity
     * limit first, so a session that has already gone stale can't be revived by this.
     */
    fun markActive() {
        if (!isLoggedIn()) return
        prefs.edit().putLong(KEY_LAST_ACTIVE, System.currentTimeMillis()).apply()
    }

    fun saveUser(u: UserInfo) {
        prefs.edit()
            .putString(KEY_USER, gson.toJson(u))
            .putString(KEY_ORG_ID, u.orgId?.takeIf { it.isNotBlank() } ?: prefs.getString(KEY_ORG_ID, null))
            .apply()
    }

    fun getUser(): UserInfo? = prefs.getString(KEY_USER, null)
        ?.let {
            try {
                gson.fromJson(it, UserInfo::class.java)
            } catch (_: Exception) {
                null
            }
        }

    fun saveOrganization(orgId: String?, orgName: String?) {
        prefs.edit()
            .putString(KEY_ORG_ID, orgId?.takeIf { it.isNotBlank() })
            .putString(KEY_ORG_NAME, orgName?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun getOrgId(): String? =
        prefs.getString(KEY_ORG_ID, null)?.takeIf { it.isNotBlank() }
            ?: getUser()?.orgId?.takeIf { it.isNotBlank() }
            ?: getToken()?.let { extractClaim(it, "org_id") }

    fun getOrgName(): String? = prefs.getString(KEY_ORG_NAME, null)

    fun isLoggedIn(): Boolean {
        val token = getToken()
        if (token.isNullOrEmpty()) return false
        // Signed out after INACTIVE_LIMIT_MS without real use. Falls back to the login time for
        // sessions created before activity tracking existed.
        val lastActive = prefs.getLong(KEY_LAST_ACTIVE, 0L)
            .takeIf { it > 0L } ?: prefs.getLong(KEY_TOKEN_TS, 0L)
        if (lastActive > 0L && System.currentTimeMillis() - lastActive > INACTIVE_LIMIT_MS) {
            clearSession()
            return false
        }
        return true
    }

    fun clearSession() {
        AppCallStore.invalidateServerCache()
        val synced = getSyncedIds()
        prefs.edit().clear().apply()
        // Keep sync dedupe across re-login on same device
        if (synced.isNotEmpty()) {
            prefs.edit().putStringSet(KEY_SYNCED, synced).apply()
        }
    }

    fun role(): String = getUser()?.normalizedRole().orEmpty()

    fun isL1OwnLeadsRole(): Boolean =
        role() in setOf("sales_representative", "marketing", "hr", "sales_executive")

    fun canViewAllOrgLeads(): Boolean =
        role() in setOf("admin", "manager", "super_admin", "org", "finance")

    fun canCreateLeads(): Boolean {
        val r = role()
        return r.isNotEmpty() && r !in setOf("student", "trainer")
    }

    fun leadsAccessLabel(): String = when {
        !hasOrgAccess() && role() == "super_admin" ->
            "No organization selected — switch org on the website first"
        isL1OwnLeadsRole() ->
            "Your assigned leads · ${getOrgName() ?: "your organization"}"
        canViewAllOrgLeads() ->
            "All leads in ${getOrgName() ?: "your organization"}"
        else ->
            "Leads for ${getOrgName() ?: "your organization"}"
    }

    fun hasOrgAccess(): Boolean = !getOrgId().isNullOrBlank()

    fun markSynced(callId: Long) {
        val set = getSyncedIds().toMutableSet()
        set.add(callId.toString())
        // Call IDs are timestamp-based, so keep the 500 newest rather than an arbitrary subset.
        val trimmed = if (set.size > 500) {
            set.sortedBy { it.toLongOrNull() ?: 0L }.takeLast(500).toSet()
        } else {
            set
        }
        prefs.edit().putStringSet(KEY_SYNCED, trimmed).apply()
    }

    fun clearSyncedIds() {
        prefs.edit().remove(KEY_SYNCED).apply()
    }

    fun isSynced(callId: Long) = getSyncedIds().contains(callId.toString())

    private fun getSyncedIds(): Set<String> =
        prefs.getStringSet(KEY_SYNCED, emptySet()) ?: emptySet()

    companion object {
        private const val LEGACY_PREFS = "sp_prefs"
        private const val SECURE_PREFS = "sp_secure_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_TOKEN_TS = "token_ts"
        private const val KEY_USER = "user"
        private const val KEY_ORG_ID = "org_id"
        private const val KEY_ORG_NAME = "org_name"
        private const val KEY_SYNCED = "synced_ids"
        private const val KEY_LAST_ACTIVE = "last_active_ts"
        /** Matches the server's MOBILE_TOKEN_IDLE_TTL (7 days). */
        private const val INACTIVE_LIMIT_MS = 7L * 24L * 60L * 60L * 1000L

        /** Fires when the server rejects the token; MainActivity sends the user to login. */
        val sessionExpired = androidx.lifecycle.MutableLiveData<Long>()

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(ctx: Context) = INSTANCE ?: synchronized(this) {
            INSTANCE ?: SessionManager(ctx.applicationContext).also { INSTANCE = it }
        }

        private fun createSecurePrefs(ctx: Context): SharedPreferences {
            return try {
                // security-crypto 1.0.0 API (MasterKey is only in 1.1.0-alpha+)
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                val secure = EncryptedSharedPreferences.create(
                    SECURE_PREFS,
                    masterKeyAlias,
                    ctx,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                migrateLegacy(ctx, secure)
                secure
            } catch (e: Exception) {
                Log.e("SessionManager", "Encrypted prefs unavailable, using private prefs", e)
                ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            }
        }

        private fun migrateLegacy(ctx: Context, secure: SharedPreferences) {
            val legacy = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            if (!legacy.contains(KEY_TOKEN) && !legacy.contains(KEY_USER)) return
            if (secure.contains(KEY_TOKEN)) {
                legacy.edit().clear().apply()
                return
            }
            secure.edit()
                .putString(KEY_TOKEN, legacy.getString(KEY_TOKEN, null))
                .putString(KEY_USER, legacy.getString(KEY_USER, null))
                .putString(KEY_ORG_ID, legacy.getString(KEY_ORG_ID, null))
                .putString(KEY_ORG_NAME, legacy.getString(KEY_ORG_NAME, null))
                .putStringSet(KEY_SYNCED, legacy.getStringSet(KEY_SYNCED, emptySet()))
                .apply()
            legacy.edit().clear().apply()
        }

        /** Decode JWT payload claim without verifying signature (UI/session only). */
        fun extractClaim(token: String, claim: String): String? {
            return try {
                val parts = token.split(".")
                if (parts.size < 2) return null
                var payload = parts[1]
                val pad = (4 - payload.length % 4) % 4
                if (pad > 0) payload += "=".repeat(pad)
                val json = String(
                    Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                val obj = JsonParser.parseString(json).asJsonObject
                obj.get(claim)?.asString?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
