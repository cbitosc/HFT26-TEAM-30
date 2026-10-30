package com.slackers.emergencymesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketCodecTest {
    private val packet = Packet(
        type = PacketType.MESSAGE,
        id = "message-1",
        senderId = "device-a",
        recipientId = "device-c",
        priority = Priority.P1_EMERGENCY,
        createdAtMillis = 1_793_400_000_000L,
        initialTtl = 8,
        retentionSeconds = Priority.P1_EMERGENCY.retentionSeconds,
        ttl = 8,
        hopCount = 0,
        encryptedBody = byteArrayOf(1, 2, 3),
        signature = byteArrayOf(4, 5),
    )

    @Test
    fun packetSurvivesEncodeAndDecode() {
        assertEquals(packet, PacketCodec.decode(PacketCodec.encode(packet)))
    }

    @Test
    fun signedBytesIgnoreMutableFieldsAndSignature() {
        val forwarded = packet.copy(ttl = 7, hopCount = 1, signature = byteArrayOf(9))
        assertArrayEquals(PacketCodec.signedBytes(packet), PacketCodec.signedBytes(forwarded))
    }

    @Test
    fun signedBytesCoverImmutableFields() {
        val changed = packet.copy(priority = Priority.P0_SOS)
        assertFalse(PacketCodec.signedBytes(packet).contentEquals(PacketCodec.signedBytes(changed)))
    }

    @Test
    fun truncatedPacketIsRejected() {
        val bytes = PacketCodec.encode(packet)
        assertThrows(MalformedPacketException::class.java) {
            PacketCodec.decode(bytes.copyOf(bytes.size - 1))
        }
    }

    @Test
    fun ttlAboveInitialTtlIsRejected() {
        val bytes = PacketCodec.encode(packet.copy(ttl = 9))
        assertThrows(MalformedPacketException::class.java) { PacketCodec.decode(bytes) }
    }

    @Test
    fun bodyWithoutLocationSurvivesEncodeAndDecode() {
        val body = MessageBody("Help at the school")
        assertEquals(body, PacketCodec.decodeBody(PacketCodec.encodeBody(body)))
    }

    @Test
    fun bodyWithLocationSurvivesEncodeAndDecode() {
        val body = MessageBody("Help", 17.39, 78.32, 12f, 1_793_400_000_000L)
        assertEquals(body, PacketCodec.decodeBody(PacketCodec.encodeBody(body)))
    }
}
