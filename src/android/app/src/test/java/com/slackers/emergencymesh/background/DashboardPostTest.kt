package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Runs the real HTTP client against a local server. Set DASHBOARD_CONTRACT_URL and
 * DASHBOARD_CONTRACT_TOKEN to also check it against a running src/dashboard service.
 */
class DashboardPostTest {
    private lateinit var server: ServerSocket
    private var status = 200
    private var requestLine = ""
    private var requestBody = ""
    private var requestHeaders = emptyMap<String, String>()

    /** A tiny HTTP server, because the JDK's own isn't on the Android test classpath. */
    @Before
    fun start() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val listening = server
        thread(isDaemon = true) {
            while (!listening.isClosed) {
                try {
                    listening.accept().use(::answer)
                } catch (_: SocketException) {
                    return@thread
                }
            }
        }
    }

    private fun answer(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val head = ByteArrayOutputStream()
        while (!head.toByteArray().decodeToString().endsWith("\r\n\r\n")) head.write(input.read())
        val lines = head.toByteArray().decodeToString().trim().split("\r\n")
        requestLine = lines.first()
        requestHeaders = lines.drop(1).associate {
            it.substringBefore(":").lowercase() to it.substringAfter(":").trim()
        }
        val body = ByteArray(requestHeaders["content-length"]?.toInt() ?: 0)
        input.readFully(body)
        requestBody = body.decodeToString()
        socket.getOutputStream().write(
            "HTTP/1.1 $status Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
        )
    }

    @After
    fun stop() = server.close()

    private fun link(token: String = "right-token") =
        DashboardLink("http://127.0.0.1:${server.localPort}", token, 0)

    private fun sos(id: String) = ChatMessage(
        messageId = id,
        conversationDeviceId = null,
        senderId = "abcd1234",
        outgoing = false,
        type = PacketType.SOS,
        priority = Priority.P0_SOS,
        body = MessageBody("दो लोग फँसे हैं · ఇద్దరు చిక్కుకున్నారు \"Gate 3\"", 17.39, 78.32),
        createdAtMillis = 1_793_415_368_052,
        status = MessageStatus.DELIVERED,
        hopCount = 2,
    )

    @Test
    fun theAlertIsPostedAsUtf8JsonWithTheToken() = runTest {
        val body = sosUploadJson(sos("a"), "Phone A")
        assertEquals(UploadOutcome.Accepted, postAlert(link(), body))
        assertEquals("POST /api/alerts HTTP/1.1", requestLine)
        assertEquals("right-token", requestHeaders["x-upload-token"])
        assertEquals("application/json", requestHeaders["content-type"])
        assertEquals(body, requestBody)
    }

    @Test
    fun aBadAlertIsRefusedForGood() = runTest {
        status = 400
        assertEquals(UploadOutcome.Refused, postAlert(link(), "{}"))
    }

    @Test
    fun aWrongTokenIsWorthRetryingAfterItIsFixed() = runTest {
        status = 401
        assertEquals(UploadOutcome.Unreachable("The upload token is wrong"), postAlert(link(), "{}"))
    }

    @Test
    fun aServerErrorIsRetried() = runTest {
        status = 500
        assertEquals(UploadOutcome.Unreachable("The dashboard returned an error"), postAlert(link(), "{}"))
    }

    @Test
    fun aDeadAddressIsUnreachable() = runTest {
        val dead = link()
        server.close()
        assertEquals(UploadOutcome.Unreachable("Can't reach the dashboard"), postAlert(dead, "{}"))
    }

    @Test
    fun theRealDashboardAcceptsAnUpload() = runTest {
        val url = System.getenv("DASHBOARD_CONTRACT_URL")
        assumeTrue(url != null)
        val token = System.getenv("DASHBOARD_CONTRACT_TOKEN").orEmpty()
        val alert = sos("contract-${System.currentTimeMillis()}")
        val target = DashboardLink(url, token, 0)
        assertEquals(UploadOutcome.Accepted, postAlert(target, sosUploadJson(alert, "Phone A")))
        assertEquals(
            UploadOutcome.Unreachable("The upload token is wrong"),
            postAlert(target.copy(token = "wrong"), sosUploadJson(alert, "Phone A")),
        )
        assertEquals(UploadOutcome.Refused, postAlert(target, "{\"alerts\":[{\"id\":\"\"}]}"))
    }
}
