package com.syncpedia.crm.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {
    fun isAvailable(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
