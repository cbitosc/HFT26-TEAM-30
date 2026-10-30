package com.slackers.emergencymesh.transport

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

sealed interface RadioProblem {
    data class MissingPermissions(val permissions: List<String>) : RadioProblem
    data class PlayServicesUnavailable(val code: Int) : RadioProblem
    data object BluetoothOff : RadioProblem
    data object WifiOff : RadioProblem
    data object LocationOff : RadioProblem
}

object RadioReadiness {
    /** Request these at runtime before calling start. SOS location is requested separately. */
    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (sdkInt >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (sdkInt >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (sdkInt >= 29) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** Returns everything that would stop or weaken phone-to-phone connections. */
    fun check(context: Context): List<RadioProblem> = buildList {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) add(RadioProblem.MissingPermissions(missing))

        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices != ConnectionResult.SUCCESS) {
            add(RadioProblem.PlayServicesUnavailable(playServices))
        }

        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
        val bluetoothOn = try {
            bluetooth?.isEnabled == true
        } catch (_: SecurityException) {
            true
        }
        if (!bluetoothOn) add(RadioProblem.BluetoothOff)

        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled != true) add(RadioProblem.WifiOff)

        if (Build.VERSION.SDK_INT < 33) {
            val location = context.getSystemService(LocationManager::class.java)
            if (location == null || !LocationManagerCompat.isLocationEnabled(location)) {
                add(RadioProblem.LocationOff)
            }
        }
    }
}
