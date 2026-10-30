package com.slackers.emergencymesh.security

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.slackers.emergencymesh.core.ContactCardChange
import com.slackers.emergencymesh.core.ContactExchange
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

private class MemoryGroupKeys : GroupKeyStore {
    var key: KeysetHandle? = null

    override fun load() = key

    override fun save(key: KeysetHandle) {
        this.key = key
    }
}

class TinkMessageSecurityTest {
    private fun phone(id: String, groupKeys: GroupKeyStore = MemoryGroupKeys()): TinkMessageSecurity {
        TinkMessageSecurity.registerTink()
        return TinkMessageSecurity(
            deviceId = id,
            displayName = "Phone $id",
            signingKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkMessageSecurity.SIGNING_TEMPLATE)),
            encryptionKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkMessageSecurity.HYBRID_TEMPLATE)),
            groupKeys = groupKeys,
        )
    }

    private val phoneA = phone("a")
    private val relayB = phone("b")
    private val phoneC = phone("c")
    private val verifiedC = phoneC.localContactCard().copy(verified = true)
    private val secret = "Trapped at the school".toByteArray()
    private val header = "immutable header".toByteArray()

    private fun directPacket(security: TinkMessageSecurity, recipient: TinkMessageSecurity): Packet {
        val unsigned = Packet(
            type = PacketType.MESSAGE,
            id = "message-1",
            senderId = "a",
            recipientId = "c",
            priority = Priority.P1_EMERGENCY,
            createdAtMillis = 1_000L,
            initialTtl = 8,
            retentionSeconds = 60,
            ttl = 8,
            hopCount = 0,
            encryptedBody = ByteArray(0),
            signature = ByteArray(0),
        )
        val body = security.encryptDirect(
            PacketCodec.encodeBody(MessageBody("Trapped at the school")),
            recipient.localContactCard().copy(verified = true),
            PacketCodec.associatedData(unsigned),
        )
        val encrypted = unsigned.copy(encryptedBody = body)
        return encrypted.copy(signature = security.sign(PacketCodec.signedBytes(encrypted)))
    }

    @Test
    fun isNotDevelopmentPlaintext() {
        assertFalse(phoneA.isDevelopmentPlaintext)
    }

    @Test
    fun recipientDecryptsDirectMessage() {
        val sent = phoneA.encryptDirect(secret, verifiedC, header)
        assertArrayEquals(secret, phoneC.decryptDirect(sent, header))
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val sent = phoneA.encryptDirect(secret, verifiedC, header)
        assertFalse(String(sent, Charsets.ISO_8859_1).contains("Trapped"))
    }

    @Test
    fun relayCannotDecryptDirectMessage() {
        val sent = phoneA.encryptDirect(secret, verifiedC, header)
        assertThrows(SecurityException::class.java) { relayB.decryptDirect(sent, header) }
    }

    @Test
    fun senderCannotDecryptItsOwnDirectMessage() {
        val sent = phoneA.encryptDirect(secret, verifiedC, header)
        assertThrows(SecurityException::class.java) { phoneA.decryptDirect(sent, header) }
    }

    @Test
    fun tamperedDirectCiphertextFails() {
        val sent = phoneA.encryptDirect(secret, verifiedC, header)
        sent[sent.size - 1] = (sent[sent.size - 1].toInt() xor 1).toByte()
        assertThrows(SecurityException::class.java) { phoneC.decryptDirect(sent, header) }
    }

    @Test
    fun changedHeaderFailsDirectDecryption() {
        val sent = phoneA.encryptDirect(secret, verifiedC, header)
        assertThrows(SecurityException::class.java) {
            phoneC.decryptDirect(sent, "changed header".toByteArray())
        }
    }

    @Test
    fun unverifiedRecipientIsRefused() {
        assertThrows(SecurityException::class.java) {
            phoneA.encryptDirect(secret, phoneC.localContactCard(), header)
        }
    }

    @Test
    fun signatureVerifiesForItsSender() {
        val signature = phoneA.sign(secret)
        assertTrue(phoneC.verify(phoneA.localContactCard(), secret, signature))
    }

    @Test
    fun signatureFailsForTheWrongSender() {
        val signature = phoneA.sign(secret)
        assertFalse(phoneC.verify(relayB.localContactCard(), secret, signature))
    }

    @Test
    fun signatureFailsForChangedData() {
        val signature = phoneA.sign(secret)
        assertFalse(phoneC.verify(phoneA.localContactCard(), header, signature))
    }

    @Test
    fun garbageKeyOrSignatureReturnsFalse() {
        val broken = phoneA.localContactCard().copy(signingPublicKey = byteArrayOf(1, 2, 3))
        assertFalse(phoneC.verify(broken, secret, phoneA.sign(secret)))
        assertFalse(phoneC.verify(phoneA.localContactCard(), secret, byteArrayOf(1)))
    }

    @Test
    fun fullPacketSurvivesTheWireAndARelay() {
        val packet = directPacket(phoneA, phoneC)
        val atRelay = PacketCodec.decode(PacketCodec.encode(packet))
        assertTrue(relayB.verify(phoneA.localContactCard(), PacketCodec.signedBytes(atRelay), atRelay.signature))
        assertThrows(SecurityException::class.java) {
            relayB.decryptDirect(atRelay.encryptedBody, PacketCodec.associatedData(atRelay))
        }

        val forwarded = atRelay.copy(ttl = atRelay.ttl - 1, hopCount = atRelay.hopCount + 1)
        val received = PacketCodec.decode(PacketCodec.encode(forwarded))
        assertTrue(phoneC.verify(phoneA.localContactCard(), PacketCodec.signedBytes(received), received.signature))
        val plain = phoneC.decryptDirect(received.encryptedBody, PacketCodec.associatedData(received))
        assertEquals("Trapped at the school", PacketCodec.decodeBody(plain).text)
    }

    @Test
    fun raisedPriorityBreaksTheSignatureAndDecryption() {
        val packet = directPacket(phoneA, phoneC)
        val tampered = packet.copy(priority = Priority.P0_SOS)
        assertFalse(phoneC.verify(phoneA.localContactCard(), PacketCodec.signedBytes(tampered), tampered.signature))
        assertThrows(SecurityException::class.java) {
            phoneC.decryptDirect(tampered.encryptedBody, PacketCodec.associatedData(tampered))
        }
    }

    @Test
    fun changedCiphertextBreaksTheSignature() {
        val packet = directPacket(phoneA, phoneC)
        val body = packet.encryptedBody.copyOf()
        body[0] = (body[0].toInt() xor 1).toByte()
        val tampered = packet.copy(encryptedBody = body)
        assertFalse(phoneC.verify(phoneA.localContactCard(), PacketCodec.signedBytes(tampered), tampered.signature))
    }

    @Test
    fun sameGroupCodeReadsSosOnEveryPhone() {
        phoneA.setGroupCode("river-bridge-42")
        relayB.setGroupCode("river-bridge-42")
        val sent = phoneA.encryptBroadcast(secret, header)
        assertArrayEquals(secret, relayB.decryptBroadcast(sent, header))
    }

    @Test
    fun differentGroupCodeCannotReadSos() {
        phoneA.setGroupCode("river-bridge-42")
        relayB.setGroupCode("other-group-code")
        val sent = phoneA.encryptBroadcast(secret, header)
        assertThrows(SecurityException::class.java) { relayB.decryptBroadcast(sent, header) }
    }

    @Test
    fun sosRequiresAGroupCode() {
        assertFalse(phoneA.hasGroupKey)
        assertThrows(SecurityException::class.java) { phoneA.encryptBroadcast(secret, header) }
        phoneA.setGroupCode("river-bridge-42")
        assertTrue(phoneA.hasGroupKey)
    }

    @Test
    fun shortGroupCodeIsRejected() {
        assertThrows(SecurityException::class.java) { phoneA.setGroupCode("  short  ") }
        assertFalse(phoneA.hasGroupKey)
    }

    @Test
    fun surroundingSpacesInGroupCodeAreIgnored() {
        phoneA.setGroupCode("river-bridge-42")
        relayB.setGroupCode("  river-bridge-42 ")
        val sent = phoneA.encryptBroadcast(secret, header)
        assertArrayEquals(secret, relayB.decryptBroadcast(sent, header))
    }

    @Test
    fun tamperedSosFails() {
        phoneA.setGroupCode("river-bridge-42")
        val sent = phoneA.encryptBroadcast(secret, header)
        sent[sent.size - 1] = (sent[sent.size - 1].toInt() xor 1).toByte()
        assertThrows(SecurityException::class.java) { phoneA.decryptBroadcast(sent, header) }
    }

    @Test
    fun groupKeyIsRestoredFromItsStore() {
        val store = MemoryGroupKeys()
        val first = phone("d", store)
        first.setGroupCode("river-bridge-42")
        val sent = first.encryptBroadcast(secret, header)

        val restarted = phone("d", store)
        assertTrue(restarted.hasGroupKey)
        assertArrayEquals(secret, restarted.decryptBroadcast(sent, header))
    }

    @Test
    fun contactCardCarriesOnlyPublicKeys() {
        val card = phoneA.localContactCard()
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(card.signingPublicKey)
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(card.encryptionPublicKey)
        assertFalse(card.verified)
    }

    @Test
    fun privateKeysetFormatIsRefusedWithoutSecretAccess() {
        val privateKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkMessageSecurity.SIGNING_TEMPLATE))
        assertThrows(GeneralSecurityException::class.java) {
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(privateKeys)
        }
    }

    @Test
    fun fingerprintMatchesAcrossPhonesAndBindsTheDeviceId() {
        val card = phoneA.localContactCard()
        assertEquals(phoneA.fingerprint(card), relayB.fingerprint(card))
        assertNotEquals(phoneA.fingerprint(card), phoneA.fingerprint(card.copy(deviceId = "other")))
        assertNotEquals(phoneA.fingerprint(card), phoneA.fingerprint(relayB.localContactCard()))
    }

    @Test
    fun contactExchangeWorksWithRealSignatures() {
        val packet = ContactExchange.buildPacket(phoneA, "packet-1", 1_000L)
        val received = PacketCodec.decode(PacketCodec.encode(packet))
        val card = ContactExchange.readPacket(phoneC, received, "a")
        assertEquals(phoneA.fingerprint(phoneA.localContactCard()), phoneC.fingerprint(card))
        assertFalse(card.verified)
    }

    @Test
    fun forgedContactCardIsRejected() {
        val packet = ContactExchange.buildPacket(phoneA, "packet-1", 1_000L)
        val forged = packet.copy(
            encryptedBody = PacketCodec.encodeContactCard(relayB.localContactCard().copy(deviceId = "a")),
        )
        assertThrows(SecurityException::class.java) { ContactExchange.readPacket(phoneC, forged, "a") }
    }

    @Test
    fun attackerKeysForAKnownDeviceAreFlagged() {
        val known = phoneA.localContactCard().copy(verified = true)
        val attacker = phone("a")
        val packet = ContactExchange.buildPacket(attacker, "packet-1", 1_000L)
        val incoming = ContactExchange.readPacket(phoneC, packet, "a")
        assertEquals(ContactCardChange.KEYS_CHANGED, ContactExchange.compare(known, incoming))
    }
}
