package com.slackers.emergencymesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {
    private val policy = ConnectionPolicy(TransportConfig())

    @Test
    fun endpointNameRoundTrips() {
        val encoded = EndpointName.encode("device-1", "Ravi")
        assertEquals(EndpointIdentity("device-1", "Ravi"), EndpointName.decode(encoded))
    }

    @Test
    fun separatorInDisplayNameIsRemoved() {
        val encoded = EndpointName.encode("device-1", "Ra|vi")
        assertEquals("Ra vi", EndpointName.decode(encoded)?.displayName)
    }

    @Test
    fun longDisplayNameFitsTheByteLimit() {
        val encoded = EndpointName.encode("device-1", "శ".repeat(200))
        assertTrue(encoded.toByteArray().size <= 131)
        assertEquals("device-1", EndpointName.decode(encoded)?.deviceId)
    }

    @Test
    fun foreignEndpointNameIsIgnored() {
        assertNull(EndpointName.decode("some other app"))
        assertNull(EndpointName.decode("em1||name"))
    }

    @Test
    fun onlyLowerIdRequestsWithoutDelay() {
        assertEquals(0, policy.initiatorDelayMillis("a", "b"))
        assertEquals(30_000, policy.initiatorDelayMillis("b", "a"))
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(0, policy.backoffMillis(0))
        assertEquals(2_000, policy.backoffMillis(1))
        assertEquals(4_000, policy.backoffMillis(2))
        assertEquals(30_000, policy.backoffMillis(20))
    }

    @Test
    fun nullAllowlistAllowsEveryone() {
        assertTrue(policy.isAllowed(null, "a"))
        assertTrue(policy.isAllowed(setOf("a"), "a"))
        assertFalse(policy.isAllowed(setOf("a"), "b"))
    }
}
