package com.slackers.emergencymesh.transport

import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LOW_ID = "aaaa-device"
private const val HIGH_ID = "zzzz-device"
private const val LOCAL_ID = "mmmm-device"

private class FakeRadio : NearbyRadio {
    override var listener: NearbyRadioListener? = null
    val requests = mutableListOf<String>()
    val accepted = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    val disconnected = mutableListOf<String>()
    val sent = mutableListOf<Long>()
    var discoveryStarts = 0
    var discoveryStops = 0
    var requestResult = RadioResult(true)
    private var nextPayloadId = 1L

    override fun startAdvertising(name: String, onResult: (RadioResult) -> Unit) =
        onResult(RadioResult(true))

    override fun startDiscovery(onResult: (RadioResult) -> Unit) {
        discoveryStarts++
        onResult(RadioResult(true))
    }

    override fun stopAdvertising() = Unit

    override fun stopDiscovery() {
        discoveryStops++
    }

    override fun stopAllEndpoints() = Unit

    override fun requestConnection(name: String, endpointId: String, onResult: (RadioResult) -> Unit) {
        requests += endpointId
        onResult(requestResult)
    }

    override fun acceptConnection(endpointId: String, onResult: (RadioResult) -> Unit) {
        accepted += endpointId
        onResult(RadioResult(true))
    }

    override fun rejectConnection(endpointId: String) {
        rejected += endpointId
    }

    override fun disconnect(endpointId: String) {
        disconnected += endpointId
    }

    override fun preparePayload(bytes: ByteArray) = OutgoingPayload(nextPayloadId++, null)

    override fun sendPayload(
        endpointId: String,
        payload: OutgoingPayload,
        onResult: (RadioResult) -> Unit,
    ) {
        sent += payload.id
        onResult(RadioResult(true))
    }
}

private class FakeStore(
    val approved: MutableSet<String> = mutableSetOf(),
    var allowlist: Set<String>? = null,
) : PeerStore {
    override fun isApproved(deviceId: String) = deviceId in approved

    override fun approve(deviceId: String) {
        approved += deviceId
    }

    override fun debugAllowlist() = allowlist

    override fun saveDebugAllowlist(deviceIds: Set<String>?) {
        allowlist = deviceIds
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyMeshTransportTest {
    private val radio = FakeRadio()
    private val store = FakeStore()

    private fun TestScope.transport(store: FakeStore = this@NearbyMeshTransportTest.store) =
        NearbyMeshTransport(radio, store, StandardTestDispatcher(testScheduler)).also {
            it.start(LOCAL_ID, "Local")
            runCurrent()
        }

    private fun name(deviceId: String) = EndpointName.encode(deviceId, "Peer")

    private val packet = Packet(
        type = PacketType.MESSAGE,
        id = "message-1",
        senderId = LOCAL_ID,
        recipientId = HIGH_ID,
        priority = Priority.P2_REGULAR,
        createdAtMillis = 1L,
        initialTtl = 5,
        retentionSeconds = 60,
        ttl = 5,
        hopCount = 0,
        encryptedBody = byteArrayOf(1),
        signature = byteArrayOf(2),
    )

    private fun TestScope.connect(endpointId: String = "e1", deviceId: String = HIGH_ID) {
        store.approved += deviceId
        radio.listener!!.onConnectionInitiated(endpointId, name(deviceId), "1234")
        radio.listener!!.onConnectionResult(endpointId, true, 0)
        runCurrent()
    }

    @Test
    fun lowerDeviceIdRequestsImmediately() = runTest {
        val transport = transport()
        radio.listener!!.onEndpointFound("e1", name(HIGH_ID))
        runCurrent()
        assertEquals(listOf("e1"), radio.requests)
        transport.stop()
    }

    @Test
    fun higherDeviceIdWaitsThenFallsBack() = runTest {
        val transport = transport()
        radio.listener!!.onEndpointFound("e1", name(LOW_ID))
        advanceTimeBy(7_000)
        runCurrent()
        assertTrue(radio.requests.isEmpty())
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(listOf("e1"), radio.requests)
        transport.stop()
    }

    @Test
    fun incomingRequestCancelsTheFallbackRequest() = runTest {
        val transport = transport()
        radio.listener!!.onEndpointFound("e1", name(LOW_ID))
        runCurrent()
        radio.listener!!.onConnectionInitiated("e1", name(LOW_ID), "1234")
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(radio.requests.isEmpty())
        transport.stop()
    }

    @Test
    fun unknownPeerWaitsForUserApprovalWithToken() = runTest {
        val transport = transport()
        radio.listener!!.onConnectionInitiated("e1", name(HIGH_ID), "1234")
        runCurrent()
        val peer = transport.peers.value.single()
        assertEquals(PeerState.AWAITING_APPROVAL, peer.state)
        assertEquals("1234", peer.authToken)
        assertTrue(radio.accepted.isEmpty())

        transport.respondToConnection("e1", accept = true)
        assertEquals(listOf("e1"), radio.accepted)
        assertFalse(store.isApproved(HIGH_ID))

        radio.listener!!.onConnectionResult("e1", true, 0)
        assertEquals(PeerState.CONNECTED, transport.peers.value.single().state)
        assertTrue(store.isApproved(HIGH_ID))
        transport.stop()
    }

    @Test
    fun slowPairingIsNotCutOffByConnectTimeout() = runTest {
        val transport = transport()
        radio.listener!!.onEndpointFound("e1", name(HIGH_ID))
        runCurrent()
        radio.listener!!.onConnectionInitiated("e1", name(HIGH_ID), "1234")
        transport.respondToConnection("e1", accept = true)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(radio.disconnected.isEmpty())
        assertEquals(PeerState.CONNECTING, transport.peers.value.single().state)
        transport.stop()
    }

    @Test
    fun requestThatNeverStartsTimesOutAndRetries() = runTest {
        val transport = transport()
        radio.listener!!.onEndpointFound("e1", name(HIGH_ID))
        runCurrent()
        advanceTimeBy(30_100)
        runCurrent()
        assertEquals(listOf("e1"), radio.disconnected)
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(2, radio.requests.size)
        transport.stop()
    }

    @Test
    fun allowlistReportsDisconnectOnlyForConnectedPeers() = runTest {
        val transport = transport()
        val events = mutableListOf<TransportEvent>()
        val collector = launch(StandardTestDispatcher(testScheduler)) {
            transport.events.collect { events += it }
        }
        runCurrent()
        radio.listener!!.onEndpointFound("e1", name(HIGH_ID))
        runCurrent()
        transport.setDebugAllowlist(setOf(LOW_ID))
        runCurrent()
        assertTrue(events.none { it is TransportEvent.PeerDisconnected })
        collector.cancel()
        transport.stop()
    }

    @Test
    fun rejectedPeerIsNotRetriedInThisSession() = runTest {
        val transport = transport()
        radio.listener!!.onConnectionInitiated("e1", name(HIGH_ID), "1234")
        transport.respondToConnection("e1", accept = false)
        radio.listener!!.onEndpointFound("e2", name(HIGH_ID))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf("e1"), radio.rejected)
        assertTrue(radio.requests.isEmpty())
        transport.stop()
    }

    @Test
    fun approvedPeerConnectsWithoutPrompt() = runTest {
        store.approved += HIGH_ID
        val transport = transport()
        radio.listener!!.onConnectionInitiated("e1", name(HIGH_ID), "1234")
        assertEquals(listOf("e1"), radio.accepted)
        transport.stop()
    }

    @Test
    fun allowlistBlocksDiscoveryAndIncomingRequests() = runTest {
        val transport = transport(FakeStore(allowlist = setOf(LOW_ID)))
        radio.listener!!.onEndpointFound("e1", name(HIGH_ID))
        radio.listener!!.onConnectionInitiated("e2", name(HIGH_ID), "1234")
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(radio.requests.isEmpty())
        assertEquals(listOf("e2"), radio.rejected)
        assertTrue(transport.peers.value.isEmpty())
        transport.stop()
    }

    @Test
    fun allowlistDropsExistingBlockedConnection() = runTest {
        val transport = transport()
        connect()
        transport.setDebugAllowlist(setOf(LOW_ID))
        assertEquals(listOf("e1"), radio.disconnected)
        assertTrue(transport.peers.value.isEmpty())
        transport.stop()
    }

    @Test
    fun sendWaitsForTransferUpdateNotJustTheTask() = runTest {
        val transport = transport()
        connect()
        val result = async { transport.send("e1", packet) }
        runCurrent()
        assertEquals(listOf(1L), radio.sent)
        assertFalse(result.isCompleted)
        radio.listener!!.onTransferUpdate(1L, TransferStatus.SUCCESS)
        runCurrent()
        assertEquals(SendResult.TransportComplete, result.await())
        transport.stop()
    }

    @Test
    fun sendToUnknownPeerFails() = runTest {
        val transport = transport()
        assertTrue(transport.send("missing", packet) is SendResult.Failed)
        transport.stop()
    }

    @Test
    fun disconnectFailsInFlightSend() = runTest {
        val transport = transport()
        connect()
        val result = async { transport.send("e1", packet) }
        runCurrent()
        radio.listener!!.onDisconnected("e1")
        runCurrent()
        assertTrue(result.await() is SendResult.Failed)
        assertEquals(PeerState.DISCONNECTED, transport.peers.value.single().state)
        transport.stop()
    }

    @Test
    fun sendTimesOutWhenNoTransferUpdateArrives() = runTest {
        val transport = transport()
        connect()
        val result = async { transport.send("e1", packet) }
        advanceTimeBy(16_000)
        runCurrent()
        assertTrue(result.await() is SendResult.Failed)
        transport.stop()
    }

    @Test
    fun incomingBytesBecomePacketEventWithEndpoint() = runTest {
        val transport = transport()
        val events = mutableListOf<TransportEvent>()
        val collector = launch(StandardTestDispatcher(testScheduler)) {
            transport.events.collect { events += it }
        }
        runCurrent()
        radio.listener!!.onBytesReceived("e1", PacketCodec.encode(packet))
        radio.listener!!.onBytesReceived("e1", byteArrayOf(1, 2, 3))
        runCurrent()
        assertEquals(TransportEvent.PacketReceived("e1", packet), events[0])
        assertTrue(events[1] is TransportEvent.MalformedData)
        collector.cancel()
        transport.stop()
    }

    @Test
    fun failedRequestRetriesWithBackoff() = runTest {
        val transport = transport()
        radio.requestResult = RadioResult(false, ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR)
        radio.listener!!.onEndpointFound("e1", name(HIGH_ID))
        runCurrent()
        assertEquals(1, radio.requests.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, radio.requests.size)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, radio.requests.size)
        transport.stop()
    }

    @Test
    fun unknownEndpointIsDroppedAfterFailedRequest() = runTest {
        val transport = transport()
        radio.requestResult = RadioResult(false, ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        radio.listener!!.onEndpointFound("e1", name(HIGH_ID))
        runCurrent()
        assertTrue(transport.peers.value.isEmpty())
        transport.stop()
    }

    @Test
    fun balancedModeScansInBursts() = runTest {
        val transport = transport()
        assertEquals(1, radio.discoveryStarts)
        advanceTimeBy(15_100)
        runCurrent()
        val stopsAfterBurst = radio.discoveryStops
        assertEquals(1, stopsAfterBurst)
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(2, radio.discoveryStarts)
        transport.stop()
    }

    @Test
    fun continuousModeKeepsScanning() = runTest {
        val transport = transport()
        transport.setDiscoveryMode(DiscoveryMode.CONTINUOUS)
        runCurrent()
        val stops = radio.discoveryStops
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(stops, radio.discoveryStops)
        transport.stop()
    }

    @Test
    fun balancedModeSkipsScanningAtTargetLinks() = runTest {
        val transport = transport()
        connect("e1", "peer-1")
        connect("e2", "peer-2")
        connect("e3", "peer-3")
        val starts = radio.discoveryStarts
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(starts, radio.discoveryStarts)
        transport.stop()
    }

    @Test
    fun disconnectStartsFreshDiscoveryForReconnect() = runTest {
        val transport = transport()
        connect()
        advanceTimeBy(20_000)
        runCurrent()
        val starts = radio.discoveryStarts
        radio.listener!!.onDisconnected("e1")
        runCurrent()
        assertEquals(starts + 1, radio.discoveryStarts)

        radio.listener!!.onEndpointFound("e9", name(HIGH_ID))
        runCurrent()
        assertEquals(listOf("e9"), radio.requests)
        transport.stop()
    }

    @Test
    fun stopFailsPendingSendsAndClearsPeers() = runTest {
        val transport = transport()
        connect()
        val result = async { transport.send("e1", packet) }
        runCurrent()
        transport.stop()
        runCurrent()
        assertTrue(result.await() is SendResult.Failed)
        assertTrue(transport.peers.value.isEmpty())
    }
}
