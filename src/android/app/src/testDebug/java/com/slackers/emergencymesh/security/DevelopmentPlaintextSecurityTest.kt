package com.slackers.emergencymesh.security

import com.slackers.emergencymesh.core.ContactExchange
import com.slackers.emergencymesh.core.PacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentPlaintextSecurityTest {
    private val phoneA = DevelopmentPlaintextSecurity("a", "A", ByteArray(32) { 1 }, ByteArray(32) { 2 })
    private val phoneB = DevelopmentPlaintextSecurity("b", "B", ByteArray(32) { 3 }, ByteArray(32) { 4 })
    private val body = "Help at the school".toByteArray()
    private val header = "header".toByteArray()

    @Test
    fun isAlwaysLabelledAsDevelopmentPlaintext() {
        assertTrue(phoneA.isDevelopmentPlaintext)
    }

    @Test
    fun directBodyRoundTripsBetweenPhones() {
        val sent = phoneA.encryptDirect(body, phoneB.localContactCard(), header)
        assertArrayEquals(body, phoneB.decryptDirect(sent, header))
    }

    @Test
    fun broadcastBodyRoundTripsBetweenPhones() {
        val sent = phoneA.encryptBroadcast(body, header)
        assertArrayEquals(body, phoneB.decryptBroadcast(sent, header))
    }

    @Test
    fun wrongHeaderIsRejected() {
        val sent = phoneA.encryptBroadcast(body, header)
        assertThrows(SecurityException::class.java) {
            phoneB.decryptBroadcast(sent, "other".toByteArray())
        }
    }

    @Test
    fun bodyWithoutTheMarkerIsRejected() {
        assertThrows(SecurityException::class.java) { phoneB.decryptDirect(body, header) }
    }

    @Test
    fun signatureVerifiesOnlyForItsOwnSenderAndBytes() {
        val signature = phoneA.sign(body)
        assertTrue(phoneB.verify(phoneA.localContactCard(), body, signature))
        assertFalse(phoneB.verify(phoneB.localContactCard(), body, signature))
        assertFalse(phoneB.verify(phoneA.localContactCard(), header, signature))
    }

    @Test
    fun fingerprintIsStableAndDiffersBetweenPhones() {
        val card = phoneA.localContactCard()
        assertEquals(phoneB.fingerprint(card), phoneA.fingerprint(card))
        assertNotEquals(phoneA.fingerprint(card), phoneA.fingerprint(phoneB.localContactCard()))
    }

    @Test
    fun contactExchangeWorksBetweenPhones() {
        val packet = ContactExchange.buildPacket(phoneA, "packet-1", 1_000L)
        val received = PacketCodec.decode(PacketCodec.encode(packet))
        val card = ContactExchange.readPacket(phoneB, received, "a")
        assertEquals("A", card.displayName)
        assertFalse(card.verified)
    }
}
