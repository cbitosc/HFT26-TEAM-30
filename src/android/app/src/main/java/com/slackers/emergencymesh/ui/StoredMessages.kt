package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.data.EmergencyMeshDao
import com.slackers.emergencymesh.data.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * M2's read-only UI bridge to M3's existing Room schema. This is NOT a
 * MessageRepository: it cannot enqueue, relay, ACK, change trust, or send.
 * A supplied real repository takes precedence over this bridge in the UI.
 * M3 currently stores local display bodies with PacketCodec.encodeBody.
 * If M3 changes that format, use its repository instead of extending this decoder.
 */
class StoredMessages(private val dao: EmergencyMeshDao) {
    val contacts: Flow<List<ContactCard>> = dao.observeContacts().map { rows ->
        rows.map { row ->
            ContactCard(row.deviceId, row.displayName, row.signingPublicKey,
                row.encryptionPublicKey, row.verified)
        }
    }

    val sosAlerts: Flow<List<ChatMessage>> = dao.observeSosMessages().map { rows ->
        rows.map { it.toChatMessage() }
    }

    fun conversation(deviceId: String): Flow<List<ChatMessage>> =
        dao.observeConversation(deviceId).map { rows -> rows.map { it.toChatMessage() } }
}

/** Invalid rows fail visibly through the UI's flow error handling, not invented defaults. */
private fun MessageEntity.toChatMessage(): ChatMessage {
    val storedPriority = Priority.entries.getOrNull(priority)
        ?: throw IllegalStateException("Stored message $messageId has an invalid priority.")
    return ChatMessage(
        messageId = messageId,
        conversationDeviceId = conversationDeviceId,
        senderId = senderId,
        outgoing = outgoing,
        type = PacketType.valueOf(type),
        priority = storedPriority,
        body = PacketCodec.decodeBody(body),
        createdAtMillis = createdAtMillis,
        status = MessageStatus.valueOf(status),
        hopCount = hopCount,
    )
}
