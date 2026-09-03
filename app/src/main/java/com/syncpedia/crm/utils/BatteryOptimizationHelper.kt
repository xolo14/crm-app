package com.syncpedia.crm.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * OEM battery managers often kill in-call capture mid-call unless the app
 * is exempt from battery optimizations.
 */
object BatteryOptimizationHelper {

    const val RC_IGNORE_BATTERY = 4102
    private const val PREFS = "battery_opt"
    private const val KEY_PROMPTED = "prompted"

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations(activity)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(intent, RC_IGNORE_BATTERY)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (_: Exception) {
            }
        }
    }

    fun promptIfNeeded(activity: Activity, force: Boolean = false) {
        if (isIgnoringBatteryOptimizations(activity) && !force) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(KEY_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
        AlertDialog.Builder(activity)
            .setTitle("Allow unrestricted battery")
            .setMessage(
                "Android may stop call recording when the screen is off unless Syncpedia CRM " +
                    "is allowed to run without battery restrictions.\n\n" +
                    "Tap Allow on the next screen."
            )
            .setPositiveButton("Allow") { _, _ ->
                requestIgnoreBatteryOptimizations(activity)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    fun statusLabel(ctx: Context): String =
        if (isIgnoringBatteryOptimizations(ctx)) {
            "Unrestricted battery · ON"
        } else {
            "Allow unrestricted battery (keeps recording alive)"
        }
}
