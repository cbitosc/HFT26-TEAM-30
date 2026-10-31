package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.TransportEvent
import com.slackers.emergencymesh.transport.LocalDevice
import com.slackers.emergencymesh.ui.EngineLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the radio and engine for the life of the process, so the mesh no longer ends with the
 * screen. [MeshForegroundService] follows [running] and ends itself once it turns false. Nothing
 * stops the service from outside, because Android crashes an app that stops a service before it
 * reaches the foreground. Call this on the main thread, like the transport.
 */
class MeshSession(
    private val transport: MeshTransport,
    private val engine: EngineLifecycle?,
    private val startKeepAlive: () -> Boolean,
    scope: CoroutineScope,
) {
    private val _running = MutableStateFlow(false)
    private val _keptAlive = MutableStateFlow(false)
    private val _problem = MutableStateFlow<String?>(null)

    /** True from the start request until [stop]. It says nothing about actual connections. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** False when Android refused the service, so the mesh must not outlive the screen. */
    val keptAlive: StateFlow<Boolean> = _keptAlive.asStateFlow()

    /** The latest radio failure. A locked phone keeps its links and retries, so it shows this. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Failure -> _problem.value = event.reason
                    is TransportEvent.PeerConnected -> _problem.value = null
                    else -> Unit
                }
            }
        }
    }

    /**
     * Call while the app is on screen, because Android refuses the service from the background.
     * Pass [serviceRunning] when the service itself is restoring the mesh.
     */
    suspend fun start(device: LocalDevice, mode: DiscoveryMode, serviceRunning: Boolean = false) {
        if (_running.value) return
        _running.value = true
        val startRadio = {
            transport.setDiscoveryMode(mode)
            transport.start(device.deviceId, device.displayName)
        }
        try {
            _keptAlive.value = serviceRunning || startKeepAlive()
            if (engine != null) engine.startBeforeTransport(startRadio) else startRadio()
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    fun stop() {
        runCatching { transport.stop() }
        runCatching { engine?.stop?.invoke() }
        runCatching { transport.setDiscoveryMode(DiscoveryMode.BALANCED) }
        _problem.value = null
        _running.value = false
    }

    /** The service reports here whether it is really in the foreground. */
    fun keepAliveChanged(active: Boolean) {
        _keptAlive.value = active
    }
}

/** A locked phone can't show a pairing prompt, so the notification says when one is waiting. */
internal fun meshStatusText(connected: Int, waiting: Int, problem: String?): String {
    val links = when (connected) {
        0 -> "Looking for nearby phones"
        1 -> "1 phone connected"
        else -> "$connected phones connected"
    }
    val approval = if (waiting > 0) " · Open the app to approve $waiting" else ""
    val failure = if (problem != null) " · Last problem: $problem" else ""
    return links + approval + failure
}
