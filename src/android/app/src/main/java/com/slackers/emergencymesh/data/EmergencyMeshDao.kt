package com.slackers.emergencymesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencyMeshDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId LIMIT 1")
    suspend fun contact(deviceId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putContact(contact: ContactEntity)

    @Query("SELECT * FROM messages WHERE conversationDeviceId = :deviceId ORDER BY createdAtMillis, messageId")
    fun observeConversation(deviceId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE type = 'SOS' ORDER BY createdAtMillis DESC")
    fun observeSosMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun message(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET hopCount = MAX(hopCount, :hopCount) WHERE messageId = :messageId")
    suspend fun updateHopCount(messageId: String, hopCount: Int)

    @Query("SELECT qp.*, m.priority AS priorityOrder FROM queued_packets qp JOIN messages m ON qp.messageId = m.messageId WHERE qp.outgoing = 1 AND (qp.recipientDeviceId IS NULL OR qp.recipientDeviceId = :peerDeviceId) AND qp.nextAttemptAtMillis <= :nowMillis ORDER BY m.priority ASC, qp.queuedAtMillis ASC LIMIT :limit")
    suspend fun dueOutgoingForPeer(peerDeviceId: String, nowMillis: Long, limit: Int): List<QueuedPacketWithPriority>

    @Query("SELECT * FROM queued_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun queuedPacket(packetId: String): QueuedPacketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putQueuedPacket(packet: QueuedPacketEntity)

    @Query("DELETE FROM queued_packets WHERE packetId = :packetId")
    suspend fun deleteQueuedPacket(packetId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE packetId = :packetId)")
    suspend fun hasSeen(packetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordSeen(packet: SeenPacketEntity): Long

    @Query("DELETE FROM seen_packets WHERE expiresAtMillis <= :nowMillis")
    suspend fun pruneSeen(nowMillis: Long)

    @Query("SELECT * FROM peer_receipts WHERE messageId = :messageId")
    suspend fun receiptsForMessage(messageId: String): List<PeerReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPeerReceipt(receipt: PeerReceiptEntity)

    /** New outgoing work is visible as Pending and durable before any send attempt. */
    @Transaction
    suspend fun saveOutgoing(message: MessageEntity, packet: QueuedPacketEntity) {
        putMessage(message)
        putQueuedPacket(packet)
    }
}

/** Projection with priority from the owning message row for SOS-first FIFO scheduling. */
data class QueuedPacketWithPriority(
    val packetId: String,
    val messageId: String,
    val recipientDeviceId: String?,
    val encodedPacket: ByteArray,
    val firstSeenAtMillis: Long,
    val queuedAtMillis: Long,
    val nextAttemptAtMillis: Long,
    val attemptCount: Int,
    val outgoing: Boolean,
    val priorityOrder: Int,
)
