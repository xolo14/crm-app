package com.syncpedia.crm.telecom

import android.os.Build
import android.telecom.Call

/** Call.getState() is deprecated from API 31; Details.state is the replacement. */
fun Call.telecomState(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        details?.state ?: @Suppress("DEPRECATION") state
    } else {
        @Suppress("DEPRECATION") state
    }
}
