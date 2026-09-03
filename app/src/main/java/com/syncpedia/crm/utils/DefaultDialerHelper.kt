package com.syncpedia.crm.utils

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.appcompat.app.AlertDialog

object DefaultDialerHelper {

    const val RC_DEFAULT_DIALER = 4101

    fun isDefaultDialer(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = ctx.getSystemService(RoleManager::class.java)
                rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER)
            } else {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                tm?.defaultDialerPackage == ctx.packageName
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Launch system UI to make Syncpedia the default Phone / dialer app. */
    fun requestDefaultDialer(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = activity.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                    !rm.isRoleHeld(RoleManager.ROLE_DIALER)
                ) {
                    activity.startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                        RC_DEFAULT_DIALER
                    )
                    return
                }
            }
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName)
            }
            activity.startActivityForResult(intent, RC_DEFAULT_DIALER)
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    fun promptIfNeeded(activity: Activity, force: Boolean = false) {
        if (isDefaultDialer(activity) && !force) return
        val prefs = activity.getSharedPreferences("dialer_role", Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean("prompted", false)) return
        prefs.edit().putBoolean("prompted", true).apply()
        AlertDialog.Builder(activity)
            .setTitle("Set Syncpedia as Phone app")
            .setMessage(
                "For two-way call recording and in-app calling, Android requires Syncpedia CRM " +
                    "to be your default Phone app.\n\n" +
                    "You can change this anytime in system settings."
            )
            .setPositiveButton("Set as default") { _, _ -> requestDefaultDialer(activity) }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Hard gate before enabling SIM recording / capture.
     * Runs [onReady] only when Syncpedia is already the default Phone app;
     * otherwise shows the system role request (and does not enable recording).
     */
    fun requireDefaultDialerForRecording(
        activity: Activity,
        onReady: () -> Unit,
        onDeferred: (() -> Unit)? = null
    ) {
        if (isDefaultDialer(activity)) {
            onReady()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Default Phone app required")
            .setMessage(
                "Call recording cannot be enabled until Syncpedia CRM is your default Phone app.\n\n" +
                    "Without this, Android blocks two-way call audio and recordings often fail " +
                    "or fall back to quiet microphone capture."
            )
            .setPositiveButton("Set as default") { _, _ ->
                onDeferred?.invoke()
                requestDefaultDialer(activity)
            }
            .setNegativeButton("Cancel") { _, _ -> onDeferred?.invoke() }
            .setCancelable(false)
            .show()
    }
}
