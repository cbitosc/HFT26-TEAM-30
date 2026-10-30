package com.slackers.emergencymesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioReadinessTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val nearbyWifi = "android.permission.NEARBY_WIFI_DEVICES"

    @Test
    fun android8UsesCoarseLocationOnly() {
        assertEquals(listOf(coarse), RadioReadiness.requiredPermissions(26))
    }

    @Test
    fun android12NeedsBluetoothAndLocation() {
        val permissions = RadioReadiness.requiredPermissions(31)
        assertTrue(scan in permissions)
        assertTrue(fine in permissions)
        assertFalse(nearbyWifi in permissions)
    }

    @Test
    fun android13NeedsNearbyWifiAndNoLocation() {
        val permissions = RadioReadiness.requiredPermissions(33)
        assertTrue(nearbyWifi in permissions)
        assertFalse(fine in permissions)
        assertFalse(coarse in permissions)
    }
}
