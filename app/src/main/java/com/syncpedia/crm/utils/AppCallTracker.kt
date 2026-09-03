package com.syncpedia.crm.utils

import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.syncpedia.crm.models.CallLogItem

/**
 * Remembers outbound calls started from Syncpedia CRM (for recording + local history).
 * Does not read the device Call Log.
 */
object AppCallTracker {

    private const val PREFS = "app_call_tracker"
    private const val KEY_PENDING = "pending_outbounds"
    private const val MATCH_WINDOW_MS = 30 * 60 * 1000L
    private const val MAX_PENDING = 80

    data class PendingCall(
        val digits: String,
        val placedAtMs: Long,
        val displayName: String = "",
        val simKey: String = "default"
    )

    private val gson = Gson()

    fun markPlaced(ctx: Context, phone: String, displayName: String = "", simKey: String = "default") {
        val digits = normalize(phone)
        if (digits.length < 3) return
        val prefs = prefs(ctx)
        val list = load(prefs).toMutableList()
        list.add(0, PendingCall(digits, System.currentTimeMillis(), displayName, simKey))
        save(prefs, list.take(MAX_PENDING))
        Log.d("AppCallTracker", "marked outbound ${digits.takeLast(4)} sim=$simKey")
    }

    fun latestPendingSimKey(ctx: Context, phone: String): String {
        val digits = normalize(phone)
        val pending = load(prefs(ctx))
        val match = pending.firstOrNull {
            phonesMatch(it.digits, digits) || digits.length < 7
        } ?: pending.firstOrNull()
        return match?.simKey ?: "default"
    }

    fun latestPendingName(ctx: Context, phone: String): String {
        val digits = normalize(phone)
        val pending = load(prefs(ctx))
        val match = pending.firstOrNull {
            phonesMatch(it.digits, digits) || digits.length < 7
        } ?: pending.firstOrNull()
        return match?.displayName.orEmpty()
    }

    fun consumeRecentPlacement(ctx: Context, phone: String): PendingCall? {
        val digits = normalize(phone)
        val prefs = prefs(ctx)
        val list = load(prefs).toMutableList()
        val now = System.currentTimeMillis()
        val idx = list.indexOfFirst { p ->
            (digits.length < 7 || phonesMatch(p.digits, digits)) &&
                now - p.placedAtMs <= MATCH_WINDOW_MS
        }
        if (idx < 0) {
            // Empty number on some OEMs — any very recent dial
            val recent = list.indexOfFirst { now - it.placedAtMs <= 5 * 60 * 1000L }
            if (recent < 0) return null
            return list.removeAt(recent).also { save(prefs, list) }
        }
        return list.removeAt(idx).also { save(prefs, list) }
    }

    fun hasRecentPlacement(ctx: Context, phone: String): Boolean {
        val digits = normalize(phone)
        val pending = load(prefs(ctx))
        val now = System.currentTimeMillis()
        if (digits.length >= 7) {
            return pending.any { p ->
                phonesMatch(p.digits, digits) && now - p.placedAtMs <= MATCH_WINDOW_MS
            }
        }
        return pending.any { now - it.placedAtMs <= 5 * 60 * 1000L }
    }

    /** @deprecated Prefer AppCallStore; kept for compile safety during migration. */
    @Suppress("UNUSED_PARAMETER")
    fun isAppOriginated(ctx: Context, item: CallLogItem): Boolean {
        if (item.callType != CallLog.Calls.OUTGOING_TYPE) return false
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    fun filterAppOriginated(ctx: Context, items: List<CallLogItem>): List<CallLogItem> = items

    fun normalize(phone: String): String = phone.filter { it.isDigit() }

    fun phonesMatch(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val minLen = 10
        if (a.length >= minLen && b.length >= minLen) {
            return a.takeLast(minLen) == b.takeLast(minLen)
        }
        return a.endsWith(b) || b.endsWith(a)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(prefs: android.content.SharedPreferences): List<PendingCall> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PendingCall>>() {}.type
            val list: List<PendingCall> = gson.fromJson(raw, type) ?: emptyList()
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            list.filter { it.placedAtMs >= cutoff }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(prefs: android.content.SharedPreferences, list: List<PendingCall>) {
        prefs.edit().putString(KEY_PENDING, gson.toJson(list)).apply()
    }
}
