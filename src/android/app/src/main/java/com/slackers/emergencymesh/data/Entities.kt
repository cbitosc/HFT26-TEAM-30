package com.slackers.emergencymesh.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo

/** A contact is trusted only after the user has verified its fingerprint. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @androidx.room.PrimaryKey val deviceId: String,
    val displayName: String,
    val signingPublicKey: ByteArray,
    val encryptionPublicKey: ByteArray,
    val verified: Boolean,
)

/** Local display record. The packet body is encoded with PacketCodec, not Gson/JSON. */
@Entity(
    tableName = "messages",
    indices = [Index("conversationDeviceId"), Index("createdAtMillis")],
)
data class MessageEntity(
    @androidx.room.PrimaryKey val messageId: String,
    val conversationDeviceId: String?,
    val senderId: String,
    val outgoing: Boolean,
    val type: String,
    val priority: Int,
    val body: ByteArray,
    val createdAtMillis: Long,
    val status: String,
    val hopCount: Int,
    val sentAtMillis: Long? = null,
    val relayedAtMillis: Long? = null,
    val deliveredAtMillis: Long? = null,
)

/** Opaque encoded packets are retained so relays never need to decrypt message bodies. */
@Entity(
    tableName = "queued_packets",
    indices = [Index(value = ["messageId"]), Index(value = ["nextAttemptAtMillis"])],
)
data class QueuedPacketEntity(
    @androidx.room.PrimaryKey val packetId: String,
    val messageId: String,
    val recipientDeviceId: String?,
    val encodedPacket: ByteArray,
    /** Priority ordinal copied from the signed packet; relays have no local message row. */
    @ColumnInfo(defaultValue = "2") val priority: Int = 2,
    /** Stable ID of the immediate peer that supplied this copy; never echo to this peer. */
    val receivedFromDeviceId: String? = null,
    /** Retention expiry measured from the first local receipt. */
    @ColumnInfo(defaultValue = "0") val expiresAtMillis: Long = Long.MAX_VALUE,
    val firstSeenAtMillis: Long,
    val queuedAtMillis: Long,
    val nextAttemptAtMillis: Long,
    val attemptCount: Int,
    val outgoing: Boolean,
)

/** Durable deduplication history, aged from the first local receipt. */
@Entity(tableName = "seen_packets", indices = [Index("expiresAtMillis")])
data class SeenPacketEntity(
    @androidx.room.PrimaryKey val packetId: String,
    val firstSeenAtMillis: Long,
    val expiresAtMillis: Long,
)

/** Per-peer receipts avoid a global "sent" bit suppressing delivery to later peers. */
@Entity(
    tableName = "peer_receipts",
    primaryKeys = ["packetId", "peerDeviceId"],
    indices = [Index("peerDeviceId")],
)
data class PeerReceiptEntity(
    val packetId: String,
    val peerDeviceId: String,
    val acknowledgedAtMillis: Long?,
    val attemptCount: Int,
    val nextAttemptAtMillis: Long,
)
