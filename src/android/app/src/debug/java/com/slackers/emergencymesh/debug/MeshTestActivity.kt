package com.slackers.emergencymesh.debug

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import com.slackers.emergencymesh.transport.LocalDevice
import com.slackers.emergencymesh.transport.LocalDeviceStore
import com.slackers.emergencymesh.transport.NearbyMeshTransport
import com.slackers.emergencymesh.transport.RadioProblem
import com.slackers.emergencymesh.transport.RadioReadiness
import kotlinx.coroutines.launch
import java.util.UUID

/** Debug-only phone test for the radio layer. Packets here are unsigned plaintext. */
class MeshTestActivity : ComponentActivity() {
    private lateinit var transport: NearbyMeshTransport
    private lateinit var device: LocalDevice

    private val problems = mutableStateListOf<RadioProblem>()
    private val messages = mutableStateListOf<String>()
    private val pingTimes = HashMap<String, Long>()
    private var running by mutableStateOf(false)
    private var continuous by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshProblems()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = LocalDeviceStore.load(this)
        transport = NearbyMeshTransport.create(this)
        refreshProblems()
        lifecycleScope.launch { transport.events.collect(::handleEvent) }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onDestroy() {
        transport.stop()
        super.onDestroy()
    }

    private fun refreshProblems() {
        problems.clear()
        problems.addAll(RadioReadiness.check(this))
    }

    private fun addMessage(line: String) {
        messages.add(0, line)
        if (messages.size > 50) messages.removeAt(messages.lastIndex)
    }

    private fun handleEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PacketReceived -> handlePacket(event.fromEndpointId, event.packet)
            is TransportEvent.MalformedData -> addMessage("MALFORMED: ${event.reason}")
            is TransportEvent.Failure -> addMessage("FAILURE: ${event.reason}")
            is TransportEvent.PeerConnected -> addMessage("CONNECTED: ${event.peer.displayName}")
            is TransportEvent.PeerDisconnected -> addMessage("DISCONNECTED: ${event.deviceId.take(8)}")
        }
    }

    private fun handlePacket(fromEndpointId: String, packet: Packet) {
        val text = PacketCodec.decodeBody(packet.encryptedBody).text
        when {
            text.startsWith(PING) -> {
                addMessage("PING from ${packet.senderId.take(8)}, replying")
                send(fromEndpointId, PONG + text.removePrefix(PING))
            }
            text.startsWith(PONG) -> {
                val startedAt = pingTimes.remove(text.removePrefix(PONG))
                val roundTrip = startedAt?.let { SystemClock.elapsedRealtime() - it }
                addMessage("PONG from ${packet.senderId.take(8)}, round trip $roundTrip ms")
            }
            else -> addMessage("TEXT from ${packet.senderId.take(8)}: $text")
        }
    }

    private fun ping(endpointId: String) {
        val id = UUID.randomUUID().toString()
        pingTimes[id] = SystemClock.elapsedRealtime()
        send(endpointId, PING + id)
    }

    private fun send(endpointId: String, text: String) {
        val packet = Packet(
            type = PacketType.MESSAGE,
            id = UUID.randomUUID().toString(),
            senderId = device.deviceId,
            recipientId = null,
            priority = Priority.P2_REGULAR,
            createdAtMillis = System.currentTimeMillis(),
            initialTtl = Priority.P2_REGULAR.defaultTtl,
            retentionSeconds = Priority.P2_REGULAR.retentionSeconds,
            ttl = Priority.P2_REGULAR.defaultTtl,
            hopCount = 0,
            encryptedBody = PacketCodec.encodeBody(MessageBody(text)),
            signature = ByteArray(0),
        )
        lifecycleScope.launch {
            val result = transport.send(endpointId, packet)
            if (result is SendResult.Failed) addMessage("SEND FAILED: ${result.reason}")
        }
    }

    private fun start() {
        refreshProblems()
        transport.start(device.deviceId, device.displayName)
        running = true
    }

    private fun stop() {
        transport.stop()
        running = false
    }

    private fun toggleMode() {
        continuous = !continuous
        transport.setDiscoveryMode(if (continuous) DiscoveryMode.CONTINUOUS else DiscoveryMode.BALANCED)
    }

    @Composable
    private fun Screen() {
        val peers by transport.peers.collectAsStateWithLifecycle()
        val log by transport.log.collectAsStateWithLifecycle()
        var name by remember { mutableStateOf(device.displayName) }
        var outgoing by remember { mutableStateOf("hello") }
        var allowlist by remember { mutableStateOf(transport.debugAllowlist) }
        var selected by remember { mutableStateOf(transport.debugAllowlist.orEmpty()) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Mesh Test: debug, unsigned plaintext", style = MaterialTheme.typography.titleMedium)
                Text("Device ${device.deviceId.take(8)}")
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Phone name (A, B or C)") },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                problems.forEach { Text("Problem: ${it.describe()}") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        permissionLauncher.launch(RadioReadiness.requiredPermissions().toTypedArray())
                    }) { Text("Permissions") }
                    OutlinedButton(onClick = ::refreshProblems) { Text("Recheck") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (running) {
                            stop()
                        } else {
                            LocalDeviceStore.saveDisplayName(this@MeshTestActivity, name)
                            device = device.copy(displayName = name)
                            start()
                        }
                    }) { Text(if (running) "Stop" else "Start") }
                    OutlinedButton(onClick = ::toggleMode) {
                        Text(if (continuous) "Mode: continuous" else "Mode: balanced")
                    }
                }
            }
            item {
                Text("Debug allowlist: ${allowlist?.size?.let { "$it allowed" } ?: "off"}")
                Text("Tick the phones allowed below, then apply. Do this on every phone.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        transport.setDebugAllowlist(selected)
                        allowlist = selected
                    }) { Text("Apply ${selected.size}") }
                    OutlinedButton(onClick = {
                        transport.setDebugAllowlist(null)
                        allowlist = null
                        selected = emptySet()
                    }) { Text("Clear") }
                }
                OutlinedTextField(
                    value = outgoing,
                    onValueChange = { outgoing = it },
                    label = { Text("Text to send") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Text("Peers (${peers.size})", style = MaterialTheme.typography.titleSmall) }
            items(peers, key = { it.deviceId }) { peer ->
                PeerCard(
                    peer = peer,
                    allowed = peer.deviceId in selected,
                    onAllowedChange = { checked ->
                        selected = if (checked) selected + peer.deviceId else selected - peer.deviceId
                    },
                    outgoing = outgoing,
                )
            }
            item { Text("Messages", style = MaterialTheme.typography.titleSmall) }
            items(messages.toList()) { Text(it) }
            item { Text("Radio log", style = MaterialTheme.typography.titleSmall) }
            items(log.takeLast(60).reversed()) { Text(it.message, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun PeerCard(
        peer: PeerInfo,
        allowed: Boolean,
        onAllowedChange: (Boolean) -> Unit,
        outgoing: String,
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${peer.displayName} (${peer.deviceId.take(8)})")
                Text("State: ${peer.state}")
                if (peer.state == PeerState.AWAITING_APPROVAL) {
                    Text("Compare code on both phones: ${peer.authToken}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { transport.respondToConnection(peer.endpointId, true) }) {
                            Text("Approve")
                        }
                        OutlinedButton(onClick = { transport.respondToConnection(peer.endpointId, false) }) {
                            Text("Reject")
                        }
                    }
                }
                if (peer.state == PeerState.CONNECTED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ping(peer.endpointId) }) { Text("Ping") }
                        OutlinedButton(onClick = { send(peer.endpointId, outgoing) }) { Text("Send") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowed, onCheckedChange = onAllowedChange)
                    Text("Allow in forced topology")
                }
            }
        }
    }

    private fun RadioProblem.describe() = when (this) {
        is RadioProblem.MissingPermissions -> "${permissions.size} permissions missing"
        is RadioProblem.PlayServicesUnavailable -> "Play services code $code"
        RadioProblem.BluetoothOff -> "Bluetooth is off"
        RadioProblem.WifiOff -> "Wi-Fi is off"
        RadioProblem.LocationOff -> "Location is off"
    }

    private companion object {
        const val PING = "ping:"
        const val PONG = "pong:"
    }
}
