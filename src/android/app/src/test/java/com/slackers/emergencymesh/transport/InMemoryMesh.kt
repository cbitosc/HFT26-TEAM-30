package com.slackers.emergencymesh.transport

import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-only network of simulated phones, for engine tests without radios. It is not real relay
 * evidence, and it can't be used in the app because it lives in the test source set.
 *
 *     val mesh = InMemoryMesh()
 *     val a = mesh.phone("A")
 *     val b = mesh.phone("B")
 *     val c = mesh.phone("C")
 *     mesh.link("A", "B")
 *     mesh.link("B", "C")
 */
class InMemoryMesh {
    private val phones = LinkedHashMap<String, SimulatedPhone>()
    private val links = HashSet<Set<String>>()

    /** Packets that crossed a link, in order, for assertions about the path taken. */
    val sentLog = mutableListOf<SentRecord>()

    data class SentRecord(val fromDeviceId: String, val toDeviceId: String, val packet: Packet)

    fun phone(deviceId: String): SimulatedPhone =
        phones.getOrPut(deviceId) { SimulatedPhone(deviceId) }

    fun link(first: String, second: String) {
        val link = setOf(first, second)
        if (!links.add(link)) return
        val a = phone(first)
        val b = phone(second)
        a.connectTo(b)
        b.connectTo(a)
    }

    fun unlink(first: String, second: String) {
        if (!links.remove(setOf(first, second))) return
        val a = phone(first)
        val b = phone(second)
        a.disconnectFrom(b)
        b.disconnectFrom(a)
    }

    inner class SimulatedPhone(val deviceId: String) : MeshTransport {
        private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
        private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)

        override val peers: StateFlow<List<PeerInfo>> = _peers
        override val events: SharedFlow<TransportEvent> = _events

        /** The endpoint ID a neighbor uses to reach this phone. */
        val endpointId: String get() = "endpoint-$deviceId"

        override fun start(localDeviceId: String, displayName: String) = Unit

        override fun stop() = Unit

        override fun respondToConnection(endpointId: String, accept: Boolean) = Unit

        override fun setDebugAllowlist(deviceIds: Set<String>?) = Unit

        override suspend fun send(endpointId: String, packet: Packet): SendResult {
            val target = phones.values.firstOrNull { it.endpointId == endpointId }
                ?: return SendResult.Failed("Unknown endpoint $endpointId")
            if (setOf(deviceId, target.deviceId) !in links) {
                return SendResult.Failed("No link to ${target.deviceId}")
            }
            sentLog += SentRecord(deviceId, target.deviceId, packet)
            val received = PacketCodec.decode(PacketCodec.encode(packet))
            target.receive(this.endpointId, received)
            return SendResult.TransportComplete
        }

        internal fun connectTo(other: SimulatedPhone) {
            val peer = PeerInfo(other.endpointId, other.deviceId, other.deviceId, PeerState.CONNECTED)
            _peers.value = _peers.value + peer
            _events.tryEmit(TransportEvent.PeerConnected(peer))
        }

        internal fun disconnectFrom(other: SimulatedPhone) {
            _peers.value = _peers.value.filterNot { it.deviceId == other.deviceId }
            _events.tryEmit(TransportEvent.PeerDisconnected(other.endpointId, other.deviceId))
        }

        private fun receive(fromEndpointId: String, packet: Packet) {
            _events.tryEmit(TransportEvent.PacketReceived(fromEndpointId, packet))
        }
    }
}
