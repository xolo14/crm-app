package com.syncpedia.crm.utils

import android.Manifest
import android.content.Context
import com.google.android.gms.location.LocationServices

class LocationHelper(private val ctx: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(ctx)

    @Suppress("MissingPermission")
    fun get(onSuccess: (Double, Double) -> Unit, onFail: () -> Unit) {
        if (!PermissionHelper.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
            onFail()
            return
        }
        client.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) onSuccess(loc.latitude, loc.longitude)
                else onFail()
            }
            .addOnFailureListener { onFail() }
    }
}
