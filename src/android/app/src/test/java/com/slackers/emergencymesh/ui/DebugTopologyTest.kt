package com.slackers.emergencymesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DebugTopologyTest {
    @Test
    fun pastedIdsUseStableIdentityAndDeduplicateAcrossSeparators() {
        assertEquals(setOf("device-B", "device-C"), topologyIds("device-B, device-C\ndevice-B", "device-A"))
    }

    @Test
    fun emptySelectionIsRepresentedAsBlockAllNotUnrestricted() {
        assertEquals(emptySet<String>(), topologyIds("", "device-A"))
    }

    @Test
    fun ownIdAndOversizedListsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { topologyIds("device-A", "device-A") }
        assertThrows(IllegalArgumentException::class.java) {
            topologyIds((1..65).joinToString(" ") { "device-$it" }, "local")
        }
    }
}
