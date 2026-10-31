package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineEventGateTest {
    @Test
    fun separatelyScheduledEngineMustBeSubscribedBeforeRadioCanStart() = runTest {
        val raw = EventsTransport()
        val gate = EngineEventGate(raw)
        var collector: Job? = null
        val lifecycle = EngineLifecycle(
            startAndAwaitReady = {
                gate.startAndAwaitSubscription {
                    // This launch deliberately runs later on the test dispatcher.
                    collector = launch { gate.events.collect { } }
                }
            },
            stop = { collector?.cancel() },
        )
        try {
            lifecycle.startBeforeTransport {
                assertEquals(1, raw.events.subscriptionCount.value)
                raw.start("local", "Phone")
            }
            assertTrue(raw.started)
        } finally { collector?.cancel() }
    }

    private class EventsTransport : MeshTransport {
        override val peers = MutableStateFlow(emptyList<PeerInfo>())
        override val events = MutableSharedFlow<TransportEvent>()
        var started = false
        override fun start(localDeviceId: String, displayName: String) { started = true }
        override fun stop() { started = false }
        override fun respondToConnection(endpointId: String, accept: Boolean) = Unit
        override suspend fun send(endpointId: String, packet: Packet) = SendResult.TransportComplete
        override fun setDebugAllowlist(deviceIds: Set<String>?) = Unit
    }
}
