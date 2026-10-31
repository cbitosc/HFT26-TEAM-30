package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastBreathTest {
    private val now = 1_000_000L
    private var sosUntil = now + 60_000
    private var sentFor = 0L
    private val sent = mutableListOf<MessageBody>()
    private var sendFails = false
    private val ownSos = mutableListOf<ChatMessage>()

    private fun sos(body: MessageBody, createdAtMillis: Long = now - 5_000, outgoing: Boolean = true) = ChatMessage(
        messageId = "sos-$createdAtMillis",
        conversationDeviceId = null,
        senderId = "me",
        outgoing = outgoing,
        type = PacketType.SOS,
        priority = Priority.P0_SOS,
        body = body,
        createdAtMillis = createdAtMillis,
        status = MessageStatus.PENDING,
        hopCount = 0,
    )

    private val lastBreath = LastBreath(
        sosUntil = { sosUntil },
        sentFor = { sentFor },
        markSent = { sentFor = it },
        alerts = { ownSos },
        send = { body ->
            if (sendFails) throw IllegalStateException("storage failed")
            sent += body
        },
        nowMillis = { now },
    )

    private val dying = BatteryState(levelPercent = 4, plugged = false)

    @Test
    fun aDyingPhoneWithAnActiveSosSendsOneFinalSos() = runTest {
        ownSos += sos(MessageBody("Trapped at Gate 3"))
        lastBreath.onBattery(dying)
        lastBreath.onBattery(BatteryState(3, false))
        assertEquals(1, sent.size)
        assertTrue(sent[0].text.contains("4%"))
        assertTrue(sent[0].text.contains("Trapped at Gate 3"))
    }

    @Test
    fun aPhoneThatNeverAskedForHelpStaysQuiet() = runTest {
        sosUntil = 0
        lastBreath.onBattery(dying)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anSosThatEndedStaysQuiet() = runTest {
        sosUntil = now - 1
        lastBreath.onBattery(dying)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aPhoneOnChargeIsNotDying() = runTest {
        lastBreath.onBattery(BatteryState(2, plugged = true))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aPhoneWithBatteryLeftWaits() = runTest {
        lastBreath.onBattery(BatteryState(6, false))
        assertTrue(sent.isEmpty())
        lastBreath.onBattery(BatteryState(5, false))
        assertEquals(1, sent.size)
    }

    @Test
    fun aRestartDoesNotSendItAgain() = runTest {
        lastBreath.onBattery(dying)
        val afterRestart = LastBreath(
            sosUntil = { sosUntil },
            sentFor = { sentFor },
            markSent = { sentFor = it },
            alerts = { ownSos },
            send = { sent += it },
            nowMillis = { now },
        )
        afterRestart.onBattery(dying)
        assertEquals(1, sent.size)
    }

    @Test
    fun aNewSosCanSendItsOwnLastBreath() = runTest {
        lastBreath.onBattery(dying)
        sosUntil += 1
        lastBreath.onBattery(dying)
        assertEquals(2, sent.size)
    }

    @Test
    fun aFailedSendIsTriedAgain() = runTest {
        sendFails = true
        runCatching { lastBreath.onBattery(dying) }
        sendFails = false
        lastBreath.onBattery(dying)
        assertEquals(1, sent.size)
    }

    @Test
    fun theLastKnownLocationIsKept() = runTest {
        ownSos += sos(MessageBody("Older", 1.0, 2.0, 30f, 900L), createdAtMillis = now - 9_000)
        ownSos += sos(MessageBody("Newer, no location"), createdAtMillis = now - 1_000)
        lastBreath.onBattery(dying)
        val body = sent.single()
        assertEquals(1.0, body.latitude!!, 0.0)
        assertEquals(2.0, body.longitude!!, 0.0)
        assertEquals(900L, body.locationTimeMillis)
        assertTrue(body.text.contains("Newer, no location"))
    }

    @Test
    fun alertsFromOtherPhonesAreNeverRepeatedAsOurOwn() = runTest {
        ownSos += sos(MessageBody("Someone else's words", 8.0, 9.0), outgoing = false)
        lastBreath.onBattery(dying)
        assertFalse(sent.single().text.contains("Someone else's words"))
        assertNull(sent.single().latitude)
    }

    @Test
    fun aLongEarlierMessageStillFitsInOnePacket() = runTest {
        ownSos += sos(MessageBody("x".repeat(MAX_TEXT_CHARS)))
        lastBreath.onBattery(dying)
        assertEquals(MAX_TEXT_CHARS, sent.single().text.length)
    }

    @Test
    fun itWorksWithoutAnEarlierMessage() = runTest {
        lastBreath.onBattery(dying)
        assertTrue(sent.single().text.startsWith("Last message from this phone"))
    }
}
