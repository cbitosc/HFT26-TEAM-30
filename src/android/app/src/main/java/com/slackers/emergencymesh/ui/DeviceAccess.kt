package com.slackers.emergencymesh.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.slackers.emergencymesh.transport.RadioProblem
import com.slackers.emergencymesh.transport.RadioReadiness
import java.util.concurrent.TimeUnit

internal fun isDebugBuild(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** M1 owns this policy and the manifest. Keep one source of truth for discovery. */
internal fun nearbyPermissions(): List<String> = RadioReadiness.requiredPermissions()

internal fun radioProblemLabel(problem: RadioProblem): String = when (problem) {
    is RadioProblem.MissingPermissions -> "Nearby access: ${problem.permissions.size} permissions missing."
    is RadioProblem.PlayServicesUnavailable -> "Google Play services is unavailable (code ${problem.code})."
    RadioProblem.BluetoothOff -> "Bluetooth is off. Turn it on for nearby connections."
    RadioProblem.WifiOff -> "Wi-Fi is off. Bluetooth-only connections may still work; Wi-Fi can improve available links."
    RadioProblem.LocationOff -> "Device location is off. Older Android versions need it for discovery."
}

internal fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun missingNearbyPermissions(context: Context): List<String> =
    nearbyPermissions().filterNot { hasPermission(context, it) }

/** Optional: the mesh runs without it, but Android then hides the running notification. */
internal fun missingNotificationPermission(context: Context): List<String> =
    if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

internal fun isBatteryRestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == false

/** Relaying with the screen off is this app's core job, which this exemption exists for. */
@SuppressLint("BatteryLife")
internal fun requestBackgroundRelay(context: Context) {
    val app = Uri.parse("package:${context.packageName}")
    try {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
    }
}

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
