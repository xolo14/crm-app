package com.syncpedia.crm.utils

import android.content.Context

/** Work-purpose call logging / recording consent. */
object ConsentPrefs {
    private const val PREFS = "call_consent"
    private const val KEY_ACCEPTED = "accepted"
    private const val KEY_ACCEPTED_AT = "accepted_at"

    fun hasAccepted(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ACCEPTED, false)

    fun accept(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_ACCEPTED, true)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
