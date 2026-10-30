package com.slackers.emergencymesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

private class FakeSecurity(private val card: ContactCard) : MessageSecurity {
    override val isDevelopmentPlaintext = true

    override fun localContactCard() = card

    override fun fingerprint(card: ContactCard) = card.deviceId

    override fun encryptDirect(plainText: ByteArray, recipient: ContactCard, associatedData: ByteArray) = plainText

    override fun decryptDirect(cipherText: ByteArray, associatedData: ByteArray) = cipherText

    override fun encryptBroadcast(plainText: ByteArray, associatedData: ByteArray) = plainText

    override fun decryptBroadcast(cipherText: ByteArray, associatedData: ByteArray) = cipherText

    override fun sign(signedBytes: ByteArray) = card.signingPublicKey + signedBytes.size.toByte()

    override fun verify(sender: ContactCard, signedBytes: ByteArray, signature: ByteArray) =
        signature.contentEquals(sender.signingPublicKey + signedBytes.size.toByte())
}

class ContactExchangeTest {
    private val cardA = ContactCard("device-a", "Asha", byteArrayOf(1, 2), byteArrayOf(3, 4), verified = true)
    private val security = FakeSecurity(cardA)
    private val packet = ContactExchange.buildPacket(security, "packet-1", 1_000L)

    @Test
    fun existingPacketTypeOrdinalsStayStable() {
        assertEquals(6, PacketType.SAFE.ordinal)
        assertEquals(7, PacketType.CONTACT_CARD.ordinal)
        assertFalse(PacketType.CONTACT_CARD.isAck)
    }

    @Test
    fun contactPacketCannotBeRelayed() {
        assertEquals(0, packet.initialTtl)
        assertEquals(0, packet.ttl)
        assertEquals(0, packet.hopCount)
    }

    @Test
    fun contactPacketSurvivesTheWireFormat() {
        val received = PacketCodec.decode(PacketCodec.encode(packet))
        val card = ContactExchange.readPacket(security, received, "device-a")
        assertEquals("device-a", card.deviceId)
        assertEquals("Asha", card.displayName)
        assertArrayEquals(cardA.signingPublicKey, card.signingPublicKey)
        assertArrayEquals(cardA.encryptionPublicKey, card.encryptionPublicKey)
    }

    @Test
    fun receivedCardAlwaysStartsUnverified() {
        assertFalse(ContactExchange.readPacket(security, packet, "device-a").verified)
    }

    @Test
    fun cardFromADifferentConnectedPhoneIsRejected() {
        assertThrows(SecurityException::class.java) {
            ContactExchange.readPacket(security, packet, "device-b")
        }
    }

    @Test
    fun cardClaimingAnotherSenderIsRejected() {
        val forged = packet.copy(senderId = "device-b")
        assertThrows(SecurityException::class.java) {
            ContactExchange.readPacket(security, forged, "device-b")
        }
    }

    @Test
    fun forwardedCardIsRejected() {
        assertThrows(SecurityException::class.java) {
            ContactExchange.readPacket(security, packet.copy(hopCount = 1), "device-a")
        }
        assertThrows(SecurityException::class.java) {
            ContactExchange.readPacket(security, packet.copy(initialTtl = 5, ttl = 4), "device-a")
        }
    }

    @Test
    fun badSignatureIsRejected() {
        assertThrows(SecurityException::class.java) {
            ContactExchange.readPacket(security, packet.copy(signature = byteArrayOf(9)), "device-a")
        }
    }

    @Test
    fun malformedCardIsRejected() {
        assertThrows(SecurityException::class.java) {
            ContactExchange.readPacket(security, packet.copy(encryptedBody = byteArrayOf(1)), "device-a")
        }
    }

    @Test
    fun otherPacketTypesAreRejected() {
        assertThrows(SecurityException::class.java) {
            ContactExchange.readPacket(security, packet.copy(type = PacketType.MESSAGE), "device-a")
        }
    }

    @Test
    fun unknownCardIsNew() {
        assertEquals(ContactCardChange.NEW, ContactExchange.compare(null, cardA))
    }

    @Test
    fun sameKeysAreUnchangedEvenWhenTheNameDiffers() {
        val renamed = cardA.copy(displayName = "New name", verified = false)
        assertEquals(ContactCardChange.UNCHANGED, ContactExchange.compare(cardA, renamed))
    }

    @Test
    fun changedKeysAreFlagged() {
        val changed = cardA.copy(signingPublicKey = byteArrayOf(9))
        assertEquals(ContactCardChange.KEYS_CHANGED, ContactExchange.compare(cardA, changed))
    }

    @Test
    fun incompleteCardIsMalformed() {
        val incomplete = PacketCodec.encodeContactCard(cardA.copy(signingPublicKey = ByteArray(0)))
        assertThrows(MalformedPacketException::class.java) {
            PacketCodec.decodeContactCard(incomplete)
        }
    }
}
