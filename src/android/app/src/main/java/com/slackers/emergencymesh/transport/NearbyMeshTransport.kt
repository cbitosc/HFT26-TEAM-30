package com.slackers.emergencymesh.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.MalformedPacketException
import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

data class TransportLogEntry(val timeMillis: Long, val message: String)

/**
 * Nearby Connections transport. All state stays on [dispatcher], which is also where Nearby
 * delivers its callbacks, so call this class from the main thread.
 */
class NearbyMeshTransport internal constructor(
    private val radio: NearbyRadio,
    private val store: PeerStore,
    private val dispatcher: CoroutineDispatcher,
    private val config: TransportConfig = TransportConfig(),
    private val logSink: (String) -> Unit = {},
) : MeshTransport {

    private class Entry(
        val endpointId: String,
        val deviceId: String,
        var displayName: String,
        var state: PeerState,
        var authToken: String? = null,
        var approvedByUser: Boolean = false,
        var connectAttempt: Int = 0,
    )

    private class PendingSend(val endpointId: String, val result: CompletableDeferred<SendResult>)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val policy = ConnectionPolicy(config)

    private val entries = LinkedHashMap<String, Entry>()
    private val failedAttempts = HashMap<String, Int>()
    private val connectJobs = HashMap<String, Job>()
    private val pendingSends = HashMap<Long, PendingSend>()
    private val rejectedDevices = HashSet<String>()

    private var running = false
    private var discovering = false
    private var discoveryJob: Job? = null
    private var mode = DiscoveryMode.BALANCED
    private var localDeviceId = ""
    private var endpointName = ""
    private var allowlist: Set<String>? = store.debugAllowlist()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 512)
    private val _log = MutableStateFlow<List<TransportLogEntry>>(emptyList())

    override val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    /** Recent radio activity for the Debug section. Message contents are never logged. */
    val log: StateFlow<List<TransportLogEntry>> = _log.asStateFlow()

    val isRunning: Boolean get() = running
    val discoveryMode: DiscoveryMode get() = mode
    val debugAllowlist: Set<String>? get() = allowlist

    private val radioListener = object : NearbyRadioListener {
        override fun onEndpointFound(endpointId: String, name: String) =
            handleEndpointFound(endpointId, name)

        override fun onEndpointLost(endpointId: String) = handleEndpointLost(endpointId)

        override fun onConnectionInitiated(endpointId: String, name: String, authDigits: String) =
            handleConnectionInitiated(endpointId, name, authDigits)

        override fun onConnectionResult(endpointId: String, connected: Boolean, code: Int) =
            handleConnectionResult(endpointId, connected, code)

        override fun onDisconnected(endpointId: String) = handleDisconnected(endpointId)

        override fun onBytesReceived(endpointId: String, bytes: ByteArray?) =
            handleBytes(endpointId, bytes)

        override fun onTransferUpdate(payloadId: Long, status: TransferStatus) {
            when (status) {
                TransferStatus.IN_PROGRESS -> Unit
                TransferStatus.SUCCESS ->
                    pendingSends[payloadId]?.result?.complete(SendResult.TransportComplete)
                TransferStatus.FAILURE ->
                    pendingSends[payloadId]?.result?.complete(SendResult.Failed("Transfer failed"))
            }
        }
    }

    override fun start(localDeviceId: String, displayName: String) {
        if (running) return
        running = true
        this.localDeviceId = localDeviceId
        val name = displayName.ifBlank { "Phone ${localDeviceId.take(4)}" }
        endpointName = EndpointName.encode(localDeviceId, name)
        radio.listener = radioListener
        log("Starting as ${localDeviceId.short()}, mode $mode")
        radio.startAdvertising(endpointName) { result ->
            when {
                result.ok -> log("Advertising started")
                result.code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING -> Unit
                else -> fail("Advertising failed: ${result.describe()}")
            }
        }
        restartDiscovery()
    }

    override fun stop() {
        if (!running) return
        running = false
        discoveryJob?.cancel()
        connectJobs.values.forEach(Job::cancel)
        connectJobs.clear()
        stopDiscovery()
        radio.stopAdvertising()
        radio.stopAllEndpoints()
        pendingSends.values.forEach { it.result.complete(SendResult.Failed("Transport stopped")) }
        pendingSends.clear()
        entries.clear()
        failedAttempts.clear()
        rejectedDevices.clear()
        publishPeers()
        log("Stopped")
    }

    override fun respondToConnection(endpointId: String, accept: Boolean) {
        val entry = entries[endpointId] ?: return
        if (entry.state != PeerState.AWAITING_APPROVAL) return
        if (accept) {
            entry.approvedByUser = true
            accept(entry)
        } else {
            log("Rejected ${entry.label()}")
            rejectedDevices += entry.deviceId
            radio.rejectConnection(endpointId)
            entries.remove(endpointId)
            publishPeers()
        }
    }

    override suspend fun send(endpointId: String, packet: Packet): SendResult =
        withContext(dispatcher) {
            val bytes = try {
                PacketCodec.encode(packet)
            } catch (e: MalformedPacketException) {
                return@withContext SendResult.Failed(e.message.orEmpty())
            }
            val entry = entries[endpointId]
            if (entry?.state != PeerState.CONNECTED) {
                return@withContext SendResult.Failed("Not connected to $endpointId")
            }
            val payload = radio.preparePayload(bytes)
            val pending = PendingSend(endpointId, CompletableDeferred())
            pendingSends[payload.id] = pending
            radio.sendPayload(endpointId, payload) { result ->
                if (!result.ok) {
                    pending.result.complete(SendResult.Failed(result.describe()))
                }
            }
            val result = withTimeoutOrNull(config.sendTimeoutMillis) { pending.result.await() }
                ?: SendResult.Failed("Timed out")
            pendingSends.remove(payload.id)
            log("Sent ${packet.summary()} to ${entry.label()}: ${result.describe()}")
            result
        }

    override fun setDebugAllowlist(deviceIds: Set<String>?) {
        allowlist = deviceIds
        store.saveDebugAllowlist(deviceIds)
        log("Debug allowlist: ${deviceIds?.joinToString { it.short() } ?: "off"}")
        rejectedDevices.clear()
        entries.values.filterNot { isAllowed(it.deviceId) }.forEach { entry ->
            connectJobs.remove(entry.deviceId)?.cancel()
            if (entry.isActive()) radio.disconnect(entry.endpointId)
            failSends(entry.endpointId, "Blocked by debug allowlist")
            entries.remove(entry.endpointId)
            if (entry.state == PeerState.CONNECTED) {
                emit(TransportEvent.PeerDisconnected(entry.endpointId, entry.deviceId))
            }
        }
        publishPeers()
        if (running) restartDiscovery()
    }

    override fun setDiscoveryMode(mode: DiscoveryMode) {
        if (this.mode == mode) return
        this.mode = mode
        log("Discovery mode $mode")
        if (running) restartDiscovery()
    }

    private fun handleEndpointFound(endpointId: String, name: String) {
        val identity = EndpointName.decode(name)
        if (identity == null) {
            log("Ignored unknown endpoint $endpointId")
            return
        }
        val deviceId = identity.deviceId
        if (deviceId == localDeviceId) return
        if (!isAllowed(deviceId)) {
            log("Allowlist blocked ${deviceId.short()}")
            return
        }
        if (deviceId in rejectedDevices) return
        val active = entries.values.firstOrNull { it.deviceId == deviceId && it.isActive() }
        if (active != null) return

        removeIdleEntries(deviceId)
        val entry = Entry(endpointId, deviceId, identity.displayName, PeerState.DISCOVERED)
        entries[endpointId] = entry
        log("Found ${entry.label()}")
        publishPeers()
        scheduleConnect(entry)
    }

    private fun handleEndpointLost(endpointId: String) {
        val entry = entries[endpointId] ?: return
        if (entry.state != PeerState.DISCOVERED) return
        log("Lost ${entry.label()}")
        connectJobs.remove(entry.deviceId)?.cancel()
        entries.remove(endpointId)
        publishPeers()
    }

    private fun handleConnectionInitiated(endpointId: String, name: String, authDigits: String) {
        val identity = EndpointName.decode(name)
        if (identity == null || identity.deviceId == localDeviceId) {
            log("Rejected invalid connection from $endpointId")
            radio.rejectConnection(endpointId)
            return
        }
        val deviceId = identity.deviceId
        if (!isAllowed(deviceId) || deviceId in rejectedDevices) {
            log("Rejected blocked device ${deviceId.short()}")
            radio.rejectConnection(endpointId)
            return
        }
        connectJobs.remove(deviceId)?.cancel()
        entries.values.filter { it.deviceId == deviceId && it.endpointId != endpointId }
            .filterNot { it.state == PeerState.CONNECTED }
            .forEach { entries.remove(it.endpointId) }

        val entry = entries.getOrPut(endpointId) {
            Entry(endpointId, deviceId, identity.displayName, PeerState.CONNECTING)
        }
        entry.displayName = identity.displayName
        entry.authToken = authDigits
        if (store.isApproved(deviceId)) {
            log("Accepting approved ${entry.label()}")
            accept(entry)
        } else {
            log("Waiting for approval of ${entry.label()}")
            entry.state = PeerState.AWAITING_APPROVAL
            publishPeers()
        }
    }

    private fun accept(entry: Entry) {
        entry.state = PeerState.CONNECTING
        publishPeers()
        radio.acceptConnection(entry.endpointId) { result ->
            if (!result.ok) log("Accept failed for ${entry.label()}: ${result.describe()}")
        }
    }

    private fun handleConnectionResult(endpointId: String, connected: Boolean, code: Int) {
        val entry = entries[endpointId] ?: return
        if (connected) {
            entry.state = PeerState.CONNECTED
            entry.authToken = null
            failedAttempts.remove(entry.deviceId)
            if (entry.approvedByUser) store.approve(entry.deviceId)
            log("Connected to ${entry.label()}")
            publishPeers()
            emit(TransportEvent.PeerConnected(entry.toPeerInfo()))
        } else {
            log("Connection failed for ${entry.label()}, code $code")
            handleConnectFailure(entry, code)
        }
    }

    private fun handleDisconnected(endpointId: String) {
        val entry = entries[endpointId] ?: return
        val wasConnected = entry.state == PeerState.CONNECTED
        entry.state = PeerState.DISCONNECTED
        log("Disconnected from ${entry.label()}")
        failSends(endpointId, "Disconnected")
        publishPeers()
        if (wasConnected) emit(TransportEvent.PeerDisconnected(endpointId, entry.deviceId))
        if (running) restartDiscovery()
    }

    private fun handleBytes(endpointId: String, bytes: ByteArray?) {
        if (bytes == null) {
            reportMalformed(endpointId, "Unsupported payload type")
            return
        }
        try {
            val packet = PacketCodec.decode(bytes)
            log("Received ${packet.summary()} from ${entries[endpointId]?.label() ?: endpointId}")
            emit(TransportEvent.PacketReceived(endpointId, packet))
        } catch (e: MalformedPacketException) {
            reportMalformed(endpointId, e.message.orEmpty())
        }
    }

    private fun reportMalformed(endpointId: String, reason: String) {
        log("Dropped malformed data from $endpointId: $reason")
        emit(TransportEvent.MalformedData(endpointId, reason))
    }

    private fun scheduleConnect(entry: Entry) {
        val deviceId = entry.deviceId
        connectJobs.remove(deviceId)?.cancel()
        val delayMillis = max(
            policy.initiatorDelayMillis(localDeviceId, deviceId),
            policy.backoffMillis(failedAttempts[deviceId] ?: 0),
        )
        connectJobs[deviceId] = scope.launch {
            delay(delayMillis)
            connectJobs.remove(deviceId)
            if (running && entries[entry.endpointId] === entry &&
                entry.state == PeerState.DISCOVERED
            ) {
                connect(entry)
            }
        }
    }

    private fun connect(entry: Entry) {
        val attempt = ++entry.connectAttempt
        entry.state = PeerState.CONNECTING
        publishPeers()
        log("Requesting ${entry.label()}")
        radio.requestConnection(endpointName, entry.endpointId) { result ->
            if (!result.ok && entry.connectAttempt == attempt) {
                log("Request failed for ${entry.label()}: ${result.describe()}")
                handleConnectFailure(entry, result.code)
            }
        }
        scope.launch {
            delay(config.connectTimeoutMillis)
            // Once Nearby reports the handshake, people may need time to compare the code.
            val stuck = entries[entry.endpointId] === entry && entry.authToken == null &&
                entry.connectAttempt == attempt && entry.state == PeerState.CONNECTING
            if (stuck) {
                log("Connection to ${entry.label()} timed out")
                radio.disconnect(entry.endpointId)
                handleConnectFailure(entry, TIMEOUT_CODE)
            }
        }
    }

    private fun handleConnectFailure(entry: Entry, code: Int) {
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return
        if (entries[entry.endpointId] !== entry) return
        entry.authToken = null
        entry.approvedByUser = false
        val failures = (failedAttempts[entry.deviceId] ?: 0) + 1
        failedAttempts[entry.deviceId] = failures
        val gone = code == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN
        if (gone || failures >= config.maxConnectAttempts || !running) {
            log("Giving up on ${entry.label()} until it is found again")
            failedAttempts.remove(entry.deviceId)
            entries.remove(entry.endpointId)
            publishPeers()
            return
        }
        entry.state = PeerState.DISCOVERED
        publishPeers()
        scheduleConnect(entry)
    }

    private fun restartDiscovery() {
        discoveryJob?.cancel()
        stopDiscovery()
        discoveryJob = scope.launch {
            while (isActive) {
                val wanted = mode == DiscoveryMode.CONTINUOUS || connectedCount() < config.targetLinks
                if (wanted) startDiscovery()
                delay(config.burstMillis)
                if (mode == DiscoveryMode.BALANCED) {
                    stopDiscovery()
                    delay(config.idleMillis)
                }
            }
        }
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        radio.startDiscovery { result ->
            when {
                result.ok -> log("Discovery started")
                result.code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING -> Unit
                else -> {
                    discovering = false
                    fail("Discovery failed: ${result.describe()}")
                }
            }
        }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        radio.stopDiscovery()
        log("Discovery paused")
    }

    private fun removeIdleEntries(deviceId: String) {
        entries.values
            .filter { it.deviceId == deviceId && !it.isActive() }
            .forEach { entries.remove(it.endpointId) }
    }

    private fun failSends(endpointId: String, reason: String) {
        pendingSends.values
            .filter { it.endpointId == endpointId }
            .forEach { it.result.complete(SendResult.Failed(reason)) }
    }

    private fun connectedCount() = entries.values.count { it.state == PeerState.CONNECTED }

    private fun isAllowed(deviceId: String) = policy.isAllowed(allowlist, deviceId)

    private fun Entry.isActive() = state == PeerState.CONNECTED ||
        state == PeerState.CONNECTING || state == PeerState.AWAITING_APPROVAL

    private fun Entry.label() = "$displayName (${deviceId.short()})"

    private fun Entry.toPeerInfo() = PeerInfo(endpointId, deviceId, displayName, state, authToken)

    private fun publishPeers() {
        _peers.value = entries.values
            .groupBy { it.deviceId }
            .map { (_, sameDevice) -> sameDevice.maxBy { it.state.rank() } }
            .map { it.toPeerInfo() }
            .sortedBy { it.displayName }
    }

    private fun PeerState.rank() = when (this) {
        PeerState.CONNECTED -> 4
        PeerState.AWAITING_APPROVAL -> 3
        PeerState.CONNECTING -> 2
        PeerState.DISCOVERED -> 1
        PeerState.DISCONNECTED -> 0
    }

    private fun emit(event: TransportEvent) {
        if (!_events.tryEmit(event)) log("Event buffer full, dropped ${event::class.simpleName}")
    }

    private fun fail(reason: String) {
        log(reason)
        emit(TransportEvent.Failure(reason))
    }

    private fun log(message: String) {
        logSink(message)
        _log.value = (_log.value + TransportLogEntry(System.currentTimeMillis(), message))
            .takeLast(MAX_LOG_ENTRIES)
    }

    private fun String.short() = take(8)

    private fun Packet.summary() = "$type ${id.short()} hop $hopCount ttl $ttl"

    private fun RadioResult.describe() = "code $code $message".trim()

    private fun SendResult.describe() = when (this) {
        SendResult.TransportComplete -> "transfer complete"
        is SendResult.Failed -> "failed, $reason"
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 300
        private const val TIMEOUT_CODE = -2

        fun create(context: Context): NearbyMeshTransport = NearbyMeshTransport(
            radio = GmsNearbyRadio(context),
            store = PrefsPeerStore(context),
            dispatcher = Dispatchers.Main.immediate,
            logSink = { Log.d("EmergencyMesh", it) },
        )
    }
}
