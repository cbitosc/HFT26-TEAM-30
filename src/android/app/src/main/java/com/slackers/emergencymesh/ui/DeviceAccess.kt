package com.slackers.emergencymesh.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/** These permissions already exist in M1's manifest. No manifest edits are needed. */
internal fun nearbyPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    // M1 declares SCAN without neverForLocation; request location for that setup.
    // Android 12+ expects COARSE and FINE to be requested together.
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
}

internal fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun missingNearbyPermissions(context: Context): List<String> =
    nearbyPermissions().filterNot { hasPermission(context, it) }

/** A bounded last-known lookup: sending SOS never waits for a GPS fix. */
internal fun recentLocation(context: Context): Location? {
    if (!hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) &&
        !hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    ) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return try {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        manager.getProviders(true)
            .mapNotNull { provider ->
                // GPS may require precise access even when coarse access was granted.
                try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }
            .filter { location ->
                val age = now - location.elapsedRealtimeNanos
                age in 0..TimeUnit.MINUTES.toNanos(2) &&
                    location.latitude.isFinite() && location.longitude.isFinite() &&
                    location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
            }
            .maxByOrNull { it.elapsedRealtimeNanos }
    } catch (_: SecurityException) {
        null
    }
}
