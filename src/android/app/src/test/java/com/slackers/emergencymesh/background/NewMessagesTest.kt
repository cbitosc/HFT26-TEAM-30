package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMessagesTest {
    private fun message(id: String) = ChatMessage(
        messageId = id,
        conversationDeviceId = null,
        senderId = "sender",
        outgoing = false,
        type = PacketType.MESSAGE,
        priority = Priority.P2_REGULAR,
        body = MessageBody(text = "hello"),
        createdAtMillis = 0,
        status = MessageStatus.DELIVERED,
        hopCount = 0,
    )

    @Test
    fun storedHistoryNeverAlerts() {
        assertTrue(NewMessages().pick(listOf(message("old-1"), message("old-2"))).isEmpty())
    }

    @Test
    fun aMessageAfterTheFirstReadAlertsOnce() {
        val newMessages = NewMessages()
        newMessages.pick(listOf(message("old")))
        val update = listOf(message("new"), message("old"))
        assertEquals(listOf("new"), newMessages.pick(update).map { it.messageId })
        assertTrue(newMessages.pick(update).isEmpty())
    }

    @Test
    fun anEmptyFirstReadStillCountsAsHistory() {
        val newMessages = NewMessages()
        assertTrue(newMessages.pick(emptyList()).isEmpty())
        assertEquals(listOf("first"), newMessages.pick(listOf(message("first"))).map { it.messageId })
    }
}
