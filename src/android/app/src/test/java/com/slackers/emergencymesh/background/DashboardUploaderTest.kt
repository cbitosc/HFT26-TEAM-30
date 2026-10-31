package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardUploaderTest {
    private val link = DashboardLink("http://192.168.1.5:8080", "token", sinceMillis = 1_000)
    private val alerts = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val contacts = MutableStateFlow<List<ContactCard>>(emptyList())
    private val target = MutableStateFlow<DashboardLink?>(link)
    private val posted = mutableListOf<String>()
    private var outcome: UploadOutcome = UploadOutcome.Accepted

    private fun alert(id: String, createdAtMillis: Long = 2_000, text: String = "Help") = ChatMessage(
        messageId = id,
        conversationDeviceId = null,
        senderId = "abcd1234",
        outgoing = false,
        type = PacketType.SOS,
        priority = Priority.P0_SOS,
        body = MessageBody(text),
        createdAtMillis = createdAtMillis,
        status = MessageStatus.DELIVERED,
        hopCount = 2,
    )

    private fun TestScope.startUploader(): DashboardUploader {
        val uploader = DashboardUploader(alerts, contacts, target, { _, body ->
            posted += body
            outcome
        }, retryMillis = 30_000)
        backgroundScope.launch { uploader.run() }
        runCurrent()
        return uploader
    }

    @Test
    fun nothingLeavesThePhoneUntilALinkIsSet() = runTest {
        target.value = null
        alerts.value = listOf(alert("a"))
        val uploader = startUploader()
        assertTrue(posted.isEmpty())
        assertTrue(uploader.status.value.startsWith("Off"))
    }

    @Test
    fun anAlertIsUploadedOnce() = runTest {
        val uploader = startUploader()
        alerts.value = listOf(alert("a"))
        runCurrent()
        alerts.value = listOf(alert("b"), alert("a"))
        runCurrent()
        assertEquals(2, posted.size)
        assertEquals("On. 2 alerts uploaded.", uploader.status.value)
    }

    @Test
    fun alertsFromBeforeTheLinkStayOnThePhone() = runTest {
        alerts.value = listOf(alert("old", createdAtMillis = 500), alert("new", createdAtMillis = 1_500))
        startUploader()
        assertEquals(1, posted.size)
        assertTrue(posted[0].contains("\"new\""))
    }

    @Test
    fun includingStoredAlertsUploadsThemToo() = runTest {
        target.value = link.copy(sinceMillis = 0)
        alerts.value = listOf(alert("old", createdAtMillis = 500))
        startUploader()
        assertEquals(1, posted.size)
    }

    @Test
    fun anUnreachableDashboardIsRetriedUntilItAnswers() = runTest {
        outcome = UploadOutcome.Unreachable("Can't reach the dashboard")
        alerts.value = listOf(alert("a"))
        val uploader = startUploader()
        assertEquals(1, posted.size)
        assertEquals("Can't reach the dashboard. Trying again soon.", uploader.status.value)

        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, posted.size)

        outcome = UploadOutcome.Accepted
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, posted.size)
        assertEquals("On. 1 alert uploaded.", uploader.status.value)
    }

    @Test
    fun aRefusedAlertCanNotHoldBackTheNextOne() = runTest {
        var calls = 0
        val uploader = DashboardUploader(alerts, contacts, target, { _, body ->
            posted += body
            if (++calls == 1) UploadOutcome.Refused else UploadOutcome.Accepted
        })
        alerts.value = listOf(alert("bad"), alert("good"))
        backgroundScope.launch { uploader.run() }
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, posted.size)
        assertEquals("On. 1 alert uploaded.", uploader.status.value)
    }

    @Test
    fun anOversizedIdIsNeverSent() = runTest {
        alerts.value = listOf(alert("x".repeat(65)))
        startUploader()
        assertTrue(posted.isEmpty())
    }

    @Test
    fun aNewDashboardGetsTheAlertsAgain() = runTest {
        alerts.value = listOf(alert("a"))
        startUploader()
        target.value = link.copy(url = "http://192.168.1.9:8080")
        runCurrent()
        assertEquals(2, posted.size)
    }

    @Test
    fun turningOffStopsUploads() = runTest {
        val uploader = startUploader()
        target.value = null
        alerts.value = listOf(alert("a"))
        runCurrent()
        assertTrue(posted.isEmpty())
        assertTrue(uploader.status.value.startsWith("Off"))
    }

    @Test
    fun theSenderNameComesFromContacts() = runTest {
        contacts.value = listOf(ContactCard("abcd1234", "Phone A", ByteArray(1), ByteArray(1)))
        alerts.value = listOf(alert("a"))
        startUploader()
        assertTrue(posted[0].contains("\"sender\":\"Phone A\""))
    }

    @Test
    fun jsonMatchesWhatTheDashboardAccepts() {
        val message = alert("sos-1", createdAtMillis = 1_500).copy(
            body = MessageBody("Two \"people\" \\ trapped\nGate 3", latitude = 17.39, longitude = 78.32),
        )
        assertEquals(
            "{\"alerts\":[{\"id\":\"sos-1\",\"text\":\"Two \\\"people\\\" \\\\ trapped\\u000aGate 3\"," +
                "\"priority\":\"P0\",\"sender\":\"Phone A\",\"hops\":2,\"created_at\":1.5," +
                "\"latitude\":17.39,\"longitude\":78.32}]}",
            sosUploadJson(message, "Phone A"),
        )
    }

    @Test
    fun hindiAndTeluguTextPassThrough() {
        val json = sosUploadJson(alert("a", text = "मदद చేయండి"), null)
        assertTrue(json.contains("\"text\":\"मदद చేయండి\""))
        assertTrue(json.contains("\"sender\":\"Phone abcd\""))
    }

    @Test
    fun aBlankSosStillReachesTheDashboard() {
        assertTrue(sosUploadJson(alert("a", text = " "), null).contains("SOS with no message"))
    }

    @Test
    fun longTextIsCutToTheDashboardLimit() {
        val json = sosUploadJson(alert("a", text = "x".repeat(2500)), null)
        assertTrue(json.contains("x".repeat(2000)))
        assertFalse(json.contains("x".repeat(2001)))
    }

    @Test
    fun invalidCoordinatesAreLeftOut() {
        for ((latitude, longitude) in listOf(91.0 to 10.0, 10.0 to 181.0, Double.NaN to 10.0, null to 10.0)) {
            val message = alert("a").copy(body = MessageBody("Help", latitude, longitude))
            assertFalse(sosUploadJson(message, null).contains("latitude"))
        }
    }

    @Test
    fun onlyWebAddressesWithAHostAreAccepted() {
        assertEquals("http://192.168.1.5:8080", normalizeDashboardUrl(" http://192.168.1.5:8080/ "))
        assertEquals("https://mesh.example.org", normalizeDashboardUrl("https://mesh.example.org"))
        assertNull(normalizeDashboardUrl("192.168.1.5:8080"))
        assertNull(normalizeDashboardUrl("ftp://mesh.example.org"))
        assertNull(normalizeDashboardUrl("http://"))
        assertNull(normalizeDashboardUrl("http://bad host"))
        assertNull(normalizeDashboardUrl(""))
    }
}
