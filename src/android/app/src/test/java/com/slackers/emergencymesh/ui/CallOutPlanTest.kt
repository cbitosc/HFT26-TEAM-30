package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.background.Ringer
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallOutPlanTest {
    private val day = Priority.P0_SOS.retentionSeconds * 1_000L
    private val now = 10 * day

    private fun sos(sender: String, createdAtMillis: Long = now, outgoing: Boolean = false) = ChatMessage(
        messageId = "$sender-$createdAtMillis",
        conversationDeviceId = null,
        senderId = sender,
        outgoing = outgoing,
        type = PacketType.SOS,
        priority = Priority.P0_SOS,
        body = MessageBody("Help"),
        createdAtMillis = createdAtMillis,
        status = MessageStatus.PENDING,
        hopCount = 1,
    )

    @Test
    fun eachPhoneIsCalledOnceNewestFirst() {
        val alerts = listOf(sos("A", now - 3_000), sos("B", now - 1_000), sos("A", now - 2_000))
        assertEquals(listOf("B", "A"), callOutTargets(alerts, now))
    }

    @Test
    fun aPhoneNeverCallsItself() {
        assertTrue(callOutTargets(listOf(sos("me", outgoing = true)), now).isEmpty())
    }

    @Test
    fun anSosPastItsLifetimeIsNotCalled() {
        val alerts = listOf(sos("old", now - day - 1), sos("recent", now - day))
        assertEquals(listOf("recent"), callOutTargets(alerts, now))
    }

    @Test
    fun phonesAreCalledOneAtATimeWithAGapBetweenThem() = runTest {
        val sent = mutableListOf<String>()
        val turns = mutableListOf<Int>()
        launch {
            callOutInTurn(listOf("A", "B", "C"), 1_000, { turn, _ -> turns += turn }) { sent += it }
        }
        runCurrent()
        assertEquals(listOf("A"), sent)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf("A"), sent)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("A", "B"), sent)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("A", "B", "C"), sent)
        assertEquals(listOf(1, 2, 3), turns)
    }

    @Test
    fun theGapIsLongerThanARingSoTwoPhonesNeverOverlap() {
        assertTrue(CALL_OUT_SPACING_MILLIS > Ringer.RING_MILLIS)
    }
}
