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
    /** Every simKey ever seen on this device — lets ensureBootstrap tell "a SIM that's
     *  genuinely new" apart from "a known SIM whose key the OS just recomputed", so turning
     *  a SIM off stays off instead of being silently re-enabled on the next app open. */
    private const val KEY_KNOWN = "known_sim_keys"

    fun keyFor(
        subscriptionId: Int? = null,
        slotIndex: Int? = null,
        handle: PhoneAccountHandle? = null
    ): String {
        // Slot index (the physical tray position) is the most stable identifier across app
        // restarts — subscriptionId can be reassigned by the OS between cold starts on some
        // OEMs, which previously caused the same physical SIM to get a different key each
        // time and made a user's "off" choice silently revert (see ensureBootstrap below).
        if (slotIndex != null && slotIndex >= 0) return "slot:$slotIndex"
        if (subscriptionId != null && subscriptionId >= 0) return "sub:$subscriptionId"
        val hid = handle?.id?.takeIf { it.isNotBlank() }
        if (hid != null) return "handle:$hid"
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
        val lines = AppDialer.listSimLines(ctx)
        val keys = lines.map { it.simKey }.toSet()
            .ifEmpty { setOf("default") }
        if (prefs.getBoolean(KEY_BOOTSTRAPPED, false) && !prefs.contains(KEY_KNOWN)) {
            // Upgrade from a build that keyed SIMs by subscriptionId and had no known-set:
            // translate the user's saved choices to the new slot keys instead of treating
            // every SIM as brand new (which would re-enable SIMs they had turned off).
            val oldEnabled = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()
            val migrated = lines.filter { line ->
                val candidates = buildSet {
                    add(line.simKey)
                    if (line.subscriptionId >= 0) add("sub:${line.subscriptionId}")
                    if (line.slotIndex >= 0) add("slot:${line.slotIndex}")
                    line.handle?.let { add(keyFor(handle = it)) }
                }
                candidates.any { it in oldEnabled }
            }.map { it.simKey }.toSet() + oldEnabled.filter { it == "default" }
            prefs.edit()
                .putStringSet(KEY_ENABLED, migrated)
                .putStringSet(KEY_KNOWN, keys)
                .apply()
            return
        }
        if (!prefs.getBoolean(KEY_BOOTSTRAPPED, false)) {
            prefs.edit()
                .putStringSet(KEY_ENABLED, keys)
                .putStringSet(KEY_KNOWN, keys)
                .putBoolean(KEY_BOOTSTRAPPED, true)
                .apply()
            return
        }
        val known = prefs.getStringSet(KEY_KNOWN, emptySet())?.toSet() ?: emptySet()
        // A key is a genuinely new SIM only the first time this device has ever seen it —
        // not merely "currently disabled", which is also true of a SIM the user turned off.
        val brandNew = keys.filter { it !in known }
        if (brandNew.isNotEmpty()) {
            val enabled = prefs.getStringSet(KEY_ENABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
            enabled.addAll(brandNew)
            prefs.edit().putStringSet(KEY_ENABLED, enabled.toSet()).apply()
        }
        if (!known.containsAll(keys)) {
            prefs.edit().putStringSet(KEY_KNOWN, (known + keys).toSet()).apply()
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
