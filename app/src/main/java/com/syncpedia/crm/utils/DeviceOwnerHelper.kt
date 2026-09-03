package com.syncpedia.crm.utils

import android.app.admin.DevicePolicyManager
import android.content.Context

object DeviceOwnerHelper {
    /** Device Owner / profile owner often get more reliable call-audio capture. */
    fun isRecordingExpectedReliable(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java) ?: return false
            dpm.isDeviceOwnerApp(ctx.packageName) || dpm.isProfileOwnerApp(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }
}
