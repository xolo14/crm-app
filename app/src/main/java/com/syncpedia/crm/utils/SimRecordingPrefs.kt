package com.syncpedia.crm.utils

import android.content.Context
import android.telecom.PhoneAccountHandle

/**
 * Which SIMs may be recorded and synced to CRM.
 * Keys are stable ids: "sub:{subscriptionId}" or "handle:{id}" or "slot:{n}".
 */
object SimRecordingPrefs {

    private const val PREFS = "sim_recording_prefs"
    private const val KEY_ENABLED = "enabled_sim_keys"
    private const val KEY_BOOTSTRAPPED = "bootstrapped"

    fun keyFor(
        subscriptionId: Int? = null,
        slotIndex: Int? = null,
        handle: PhoneAccountHandle? = null
    ): String {
        if (subscriptionId != null && subscriptionId >= 0) return "sub:$subscriptionId"
        val hid = handle?.id?.takeIf { it.isNotBlank() }
        if (hid != null) return "handle:$hid"
        if (slotIndex != null && slotIndex >= 0) return "slot:$slotIndex"
        return "default"
    }

    fun getEnabledKeys(ctx: Context): Set<String> {
        val prefs = prefs(ctx)
        ensureBootstrap(ctx)
        return prefs.getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()
    }

    fun setEnabledKeys(ctx: Context, keys: Set<String>) {
        prefs(ctx).edit()
            .putStringSet(KEY_ENABLED, keys.toSet())
            .putBoolean(KEY_BOOTSTRAPPED, true)
            .apply()
    }

    fun isSimAllowed(ctx: Context, simKey: String?): Boolean {
        ensureBootstrap(ctx)
        val key = simKey?.takeIf { it.isNotBlank() } ?: "default"
        val enabled = getEnabledKeys(ctx)
        if (enabled.isEmpty()) return false
        if (key in enabled) return true
        if (key == "default") return enabled.isNotEmpty()
        // Cross-match sub:/handle:/slot: for the same physical SIM
        val lines = AppDialer.listSimLines(ctx)
        val line = lines.firstOrNull { it.simKey == key }
            ?: lines.firstOrNull {
                keyFor(handle = it.handle) == key ||
                    (it.slotIndex >= 0 && key == "slot:${it.slotIndex}") ||
                    (it.subscriptionId >= 0 && key == "sub:${it.subscriptionId}")
            }
        if (line != null) {
            val candidates = buildSet {
                add(line.simKey)
                if (line.subscriptionId >= 0) add("sub:${line.subscriptionId}")
                if (line.slotIndex >= 0) add("slot:${line.slotIndex}")
                line.handle?.let { add(keyFor(handle = it)) }
            }
            if (candidates.any { it in enabled }) return true
        }
        // If every listed SIM is enabled, allow unknown keys too
        if (lines.isNotEmpty() && lines.all { it.simKey in enabled }) return true
        return false
    }

    fun isHandleAllowed(ctx: Context, handle: PhoneAccountHandle?): Boolean {
        if (handle == null) {
            // Unknown SIM — only allow if every known SIM is enabled, or default
            val enabled = getEnabledKeys(ctx)
            return enabled.contains("default") || enabled.isNotEmpty()
        }
        val lines = AppDialer.listSimLines(ctx)
        val match = lines.firstOrNull { it.handle == handle }
        val key = match?.simKey ?: keyFor(handle = handle)
        return isSimAllowed(ctx, key)
    }

    /** First launch: enable all currently available SIMs. Also enable newly appeared SIMs. */
    fun ensureBootstrap(ctx: Context) {
        val prefs = prefs(ctx)
        val keys = AppDialer.listSimLines(ctx).map { it.simKey }.toSet()
            .ifEmpty { setOf("default") }
        if (!prefs.getBoolean(KEY_BOOTSTRAPPED, false)) {
            prefs.edit()
                .putStringSet(KEY_ENABLED, keys)
                .putBoolean(KEY_BOOTSTRAPPED, true)
                .apply()
            return
        }
        // Auto-enable SIMs that appear after first launch (don't leave them silently off)
        val enabled = prefs.getStringSet(KEY_ENABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        var changed = false
        for (k in keys) {
            if (k !in enabled && enabled.isNotEmpty()) {
                // Only auto-add if user hasn't turned everything off
                val anyOn = enabled.isNotEmpty()
                if (anyOn && keys.any { it in enabled }) {
                    enabled.add(k)
                    changed = true
                }
            }
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_ENABLED, enabled.toSet()).apply()
        }
    }

    fun summaryLabel(ctx: Context): String {
        ensureBootstrap(ctx)
        val lines = AppDialer.listSimLines(ctx)
        val enabled = getEnabledKeys(ctx)
        val on = lines.filter { it.simKey in enabled }
        return when {
            lines.isEmpty() -> "No SIMs found"
            on.isEmpty() -> "Off — no SIMs selected"
            on.size == lines.size -> "All SIMs (${on.size})"
            else -> on.joinToString(", ") { it.slotShort }
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
