package com.slackers.emergencymesh.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

const val PACKET_VERSION = 1

/** Nearby BYTES payloads are limited to 32 KB. */
const val MAX_PACKET_BYTES = 32 * 1024
const val MAX_TEXT_CHARS = 2_000
const val MAX_TTL = 10

enum class PacketType {
    MESSAGE, SOS, ACK_SENT, ACK_RELAYED, ACK_DELIVERED, SOS_CANCEL, SAFE, CONTACT_CARD, CALL_OUT,
    TALK_BACK;

    val isAck: Boolean
        get() = this == ACK_SENT || this == ACK_RELAYED || this == ACK_DELIVERED

    /** Text for one person, which is end-to-end encrypted and confirmed by its recipient. */
    val isDirectText: Boolean
        get() = this == MESSAGE || this == TALK_BACK
}

/** Declared most urgent first, so sorting by ordinal gives queue order. */
enum class Priority(val defaultTtl: Int, val retentionSeconds: Int) {
    P0_SOS(10, 24 * 60 * 60),
    P1_EMERGENCY(8, 12 * 60 * 60),
    P2_REGULAR(5, 6 * 60 * 60),
}

/**
 * [id] is the packet UUID. For MESSAGE and SOS it is also the message UUID.
 * ACKs have their own [id] and point at the original through [refMessageId].
 * Only [ttl] and [hopCount] may change in transit.
 */
data class Packet(
    val version: Int = PACKET_VERSION,
    val type: PacketType,
    val id: String,
    val senderId: String,
    val recipientId: String?,
    val refMessageId: String? = null,
    val priority: Priority,
    val createdAtMillis: Long,
    val initialTtl: Int,
    val retentionSeconds: Int,
    val ttl: Int,
    val hopCount: Int,
    val encryptedBody: ByteArray,
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Packet &&
            version == other.version &&
            type == other.type &&
            id == other.id &&
            senderId == other.senderId &&
            recipientId == other.recipientId &&
            refMessageId == other.refMessageId &&
            priority == other.priority &&
            createdAtMillis == other.createdAtMillis &&
            initialTtl == other.initialTtl &&
            retentionSeconds == other.retentionSeconds &&
            ttl == other.ttl &&
            hopCount == other.hopCount &&
            encryptedBody.contentEquals(other.encryptedBody) &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int = id.hashCode()
}

/** The plaintext carried inside [Packet.encryptedBody]. */
data class MessageBody(
    val text: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationTimeMillis: Long? = null,
)

class MalformedPacketException(message: String) : IOException(message)

object PacketCodec {
    private const val MAGIC = 0x454D // "EM"

    fun encode(packet: Packet): ByteArray {
        val bytes = write { out ->
            out.writeShort(MAGIC)
            writeImmutable(out, packet)
            out.writeByte(packet.ttl)
            out.writeByte(packet.hopCount)
            writeBytes(out, packet.encryptedBody)
            writeBytes(out, packet.signature)
        }
        if (bytes.size > MAX_PACKET_BYTES) {
            throw MalformedPacketException("Packet is ${bytes.size} bytes, limit is $MAX_PACKET_BYTES")
        }
        return bytes
    }

    fun decode(bytes: ByteArray): Packet {
        if (bytes.size > MAX_PACKET_BYTES) throw MalformedPacketException("Packet too large")
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readUnsignedShort() != MAGIC) throw MalformedPacketException("Bad magic")
            val version = input.readUnsignedByte()
            if (version != PACKET_VERSION) throw MalformedPacketException("Unsupported version $version")
            val type = enumAt<PacketType>(input.readUnsignedByte())
            val id = readString(input)
            val senderId = readString(input)
            val recipientId = readNullableString(input)
            val refMessageId = readNullableString(input)
            val priority = enumAt<Priority>(input.readUnsignedByte())
            val createdAtMillis = input.readLong()
            val initialTtl = input.readUnsignedByte()
            val retentionSeconds = input.readInt()
            val ttl = input.readUnsignedByte()
            val hopCount = input.readUnsignedByte()
            val body = readBytes(input)
            val signature = readBytes(input)
            if (input.available() != 0) throw MalformedPacketException("Trailing bytes")
            if (initialTtl > MAX_TTL || ttl > initialTtl || retentionSeconds <= 0) {
                throw MalformedPacketException("Invalid hop or retention values")
            }
            return Packet(
                version, type, id, senderId, recipientId, refMessageId, priority,
                createdAtMillis, initialTtl, retentionSeconds, ttl, hopCount, body, signature,
            )
        } catch (e: EOFException) {
            throw MalformedPacketException("Truncated packet")
        }
    }

    /** Bytes to sign: every field except [Packet.signature], [Packet.ttl] and [Packet.hopCount]. */
    fun signedBytes(packet: Packet): ByteArray = write { out ->
        writeImmutable(out, packet)
        writeBytes(out, packet.encryptedBody)
    }

    /** Associated data for encryption: the immutable header, without the ciphertext. */
    fun associatedData(packet: Packet): ByteArray = write { out -> writeImmutable(out, packet) }

    fun encodeBody(body: MessageBody): ByteArray = write { out ->
        writeString(out, body.text)
        out.writeBoolean(body.latitude != null && body.longitude != null)
        if (body.latitude != null && body.longitude != null) {
            out.writeDouble(body.latitude)
            out.writeDouble(body.longitude)
            out.writeFloat(body.accuracyMeters ?: -1f)
            out.writeLong(body.locationTimeMillis ?: 0L)
        }
    }

    fun decodeBody(bytes: ByteArray): MessageBody {
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val text = readString(input)
            if (!input.readBoolean()) return MessageBody(text)
            val latitude = input.readDouble()
            val longitude = input.readDouble()
            val accuracy = input.readFloat()
            val time = input.readLong()
            return MessageBody(
                text, latitude, longitude,
                accuracy.takeIf { it >= 0f },
                time.takeIf { it > 0L },
            )
        } catch (e: EOFException) {
            throw MalformedPacketException("Truncated message body")
        }
    }

    /** The verified flag is local, so it is never sent. */
    fun encodeContactCard(card: ContactCard): ByteArray = write { out ->
        writeString(out, card.deviceId)
        writeString(out, card.displayName)
        writeBytes(out, card.signingPublicKey)
        writeBytes(out, card.encryptionPublicKey)
    }

    /** A received card always starts unverified. */
    fun decodeContactCard(bytes: ByteArray): ContactCard {
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val card = ContactCard(
                deviceId = readString(input),
                displayName = readString(input),
                signingPublicKey = readBytes(input),
                encryptionPublicKey = readBytes(input),
                verified = false,
            )
            if (input.available() != 0) throw MalformedPacketException("Trailing contact bytes")
            if (card.deviceId.isBlank() || card.signingPublicKey.isEmpty() ||
                card.encryptionPublicKey.isEmpty()
            ) {
                throw MalformedPacketException("Incomplete contact card")
            }
            return card
        } catch (e: EOFException) {
            throw MalformedPacketException("Truncated contact card")
        }
    }

    private fun writeImmutable(out: DataOutputStream, packet: Packet) {
        out.writeByte(packet.version)
        out.writeByte(packet.type.ordinal)
        writeString(out, packet.id)
        writeString(out, packet.senderId)
        writeNullableString(out, packet.recipientId)
        writeNullableString(out, packet.refMessageId)
        out.writeByte(packet.priority.ordinal)
        out.writeLong(packet.createdAtMillis)
        out.writeByte(packet.initialTtl)
        out.writeInt(packet.retentionSeconds)
    }

    private fun write(block: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > MAX_PACKET_BYTES) throw MalformedPacketException("Invalid length $size")
        return ByteArray(size).also(input::readFully)
    }

    private fun writeString(out: DataOutputStream, value: String) =
        writeBytes(out, value.toByteArray(Charsets.UTF_8))

    private fun readString(input: DataInputStream): String =
        String(readBytes(input), Charsets.UTF_8)

    private fun writeNullableString(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) writeString(out, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private inline fun <reified T : Enum<T>> enumAt(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal)
            ?: throw MalformedPacketException("Unknown ${T::class.simpleName} $ordinal")
}
