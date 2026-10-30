package com.slackers.emergencymesh.transport

import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryMeshTest {
    private val packet = Packet(
        type = PacketType.MESSAGE,
        id = "message-1",
        senderId = "A",
        recipientId = "C",
        priority = Priority.P2_REGULAR,
        createdAtMillis = 1L,
        initialTtl = 5,
        retentionSeconds = 60,
        ttl = 5,
        hopCount = 0,
        encryptedBody = byteArrayOf(1),
        signature = byteArrayOf(2),
    )

    @Test
    fun linkedPhonesExchangePackets() = runTest {
        val mesh = InMemoryMesh()
        val a = mesh.phone("A")
        val b = mesh.phone("B")
        mesh.link("A", "B")

        val received = mutableListOf<TransportEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            b.events.collect { received += it }
        }

        assertEquals(SendResult.TransportComplete, a.send(b.endpointId, packet))
        assertEquals(TransportEvent.PacketReceived(a.endpointId, packet), received.single())
        collector.cancel()
    }

    @Test
    fun unlinkedPhonesCannotReachEachOther() = runTest {
        val mesh = InMemoryMesh()
        val a = mesh.phone("A")
        val b = mesh.phone("B")
        val c = mesh.phone("C")
        mesh.link("A", "B")
        mesh.link("B", "C")

        assertEquals(PeerState.CONNECTED, b.peers.value.first().state)
        assertEquals(2, b.peers.value.size)
        assertTrue(a.send(c.endpointId, packet) is SendResult.Failed)
    }

    @Test
    fun unlinkRemovesPeerAndBlocksSending() = runTest {
        val mesh = InMemoryMesh()
        val a = mesh.phone("A")
        val b = mesh.phone("B")
        mesh.link("A", "B")
        mesh.unlink("A", "B")

        assertTrue(a.peers.value.isEmpty())
        assertTrue(a.send(b.endpointId, packet) is SendResult.Failed)
    }
}
