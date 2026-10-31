package com.slackers.emergencymesh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.slackers.emergencymesh.core.MessageStatus
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

    @Query("SELECT * FROM messages WHERE type = 'AREA_ALERT' ORDER BY createdAtMillis DESC")
    fun observeAreaAlerts(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE outgoing = 0 ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecentIncoming(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun message(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    fun observeMessage(messageId: String): Flow<MessageEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET sentAtMillis = COALESCE(sentAtMillis, :atMillis) WHERE messageId = :messageId")
    suspend fun recordSentAt(messageId: String, atMillis: Long)

    @Query("UPDATE messages SET relayedAtMillis = COALESCE(relayedAtMillis, :atMillis) WHERE messageId = :messageId")
    suspend fun recordRelayedAt(messageId: String, atMillis: Long)

    @Query("UPDATE messages SET deliveredAtMillis = COALESCE(deliveredAtMillis, :atMillis) WHERE messageId = :messageId")
    suspend fun recordDeliveredAt(messageId: String, atMillis: Long)

    /** Keep status monotonic while persisting the first time each milestone is observed. */
    @Transaction
    suspend fun advanceMessageStatus(messageId: String, status: MessageStatus, atMillis: Long) {
        val current = message(messageId) ?: return
        val currentStatus = runCatching { MessageStatus.valueOf(current.status) }
            .getOrDefault(MessageStatus.PENDING)
        val advancesStatus = status.ordinal > currentStatus.ordinal
        if (advancesStatus) updateStatus(messageId, status.name)
        if (!advancesStatus && status != currentStatus) return
        when (status) {
            MessageStatus.PENDING -> Unit
            MessageStatus.SENT -> recordSentAt(messageId, atMillis)
            MessageStatus.RELAYED -> recordRelayedAt(messageId, atMillis)
            MessageStatus.DELIVERED -> recordDeliveredAt(messageId, atMillis)
        }
    }

    @Query("UPDATE messages SET hopCount = MAX(hopCount, :hopCount) WHERE messageId = :messageId")
    suspend fun updateHopCount(messageId: String, hopCount: Int)

    /**
     * Flood retained packets toward any eligible peer, not only the final recipient.
     * A per-peer receipt suppresses copies already acknowledged by this peer and
     * supplies that peer's independent retry time.
     */
    @Query("""
        SELECT q.* FROM queued_packets AS q
        LEFT JOIN peer_receipts AS r
          ON r.packetId = q.packetId AND r.peerDeviceId = :peerDeviceId
        WHERE q.outgoing = 1
          AND q.expiresAtMillis > :nowMillis
          AND (q.receivedFromDeviceId IS NULL OR q.receivedFromDeviceId != :peerDeviceId)
          AND (
            (r.packetId IS NULL AND q.nextAttemptAtMillis <= :nowMillis)
            OR (r.packetId IS NOT NULL AND r.acknowledgedAtMillis IS NULL
                AND r.nextAttemptAtMillis <= :nowMillis)
          )
        ORDER BY q.priority ASC, q.queuedAtMillis ASC
        LIMIT :limit
        """)
    suspend fun dueOutgoingForPeer(peerDeviceId: String, nowMillis: Long, limit: Int): List<QueuedPacketEntity>

    @Query("SELECT * FROM queued_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun queuedPacket(packetId: String): QueuedPacketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putQueuedPacket(packet: QueuedPacketEntity)

    @Query("DELETE FROM queued_packets WHERE packetId = :packetId")
    suspend fun deleteQueuedPacket(packetId: String)

    @Query("DELETE FROM queued_packets WHERE expiresAtMillis <= :nowMillis")
    suspend fun pruneExpiredPackets(nowMillis: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE packetId = :packetId)")
    suspend fun hasSeen(packetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordSeen(packet: SeenPacketEntity): Long

    @Query("DELETE FROM seen_packets WHERE expiresAtMillis <= :nowMillis")
    suspend fun pruneSeen(nowMillis: Long)

    @Query("SELECT * FROM peer_receipts WHERE packetId = :packetId")
    suspend fun receiptsForPacket(packetId: String): List<PeerReceiptEntity>

    @Query("SELECT * FROM peer_receipts WHERE packetId = :packetId AND peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun receipt(packetId: String, peerDeviceId: String): PeerReceiptEntity?

    @Query("UPDATE peer_receipts SET acknowledgedAtMillis = :acknowledgedAtMillis WHERE packetId = :packetId AND peerDeviceId = :peerDeviceId")
    suspend fun acknowledgePacketForPeer(packetId: String, peerDeviceId: String, acknowledgedAtMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPeerReceipt(receipt: PeerReceiptEntity)

    @Query("DELETE FROM peer_receipts WHERE packetId NOT IN (SELECT packetId FROM queued_packets)")
    suspend fun pruneOrphanedPeerReceipts()

    /** New outgoing work is visible as Pending and durable before any send attempt. */
    @Transaction
    suspend fun saveOutgoing(message: MessageEntity, packet: QueuedPacketEntity) {
        putMessage(message)
        putQueuedPacket(packet)
    }
}
