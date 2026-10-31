package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import com.slackers.emergencymesh.transport.LocalDevice
import com.slackers.emergencymesh.ui.EngineLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshSessionTest {
    private val device = LocalDevice("device-1", "Phone A")
    private val calls = mutableListOf<String>()
    private val transport = RecordingTransport(calls)

    private fun TestScope.session(
        engine: EngineLifecycle? = recordingEngine(),
        keepAlive: Boolean = true,
    ): MeshSession {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return MeshSession(transport, engine, { calls += "keep-alive"; keepAlive }, scope)
    }

    private fun recordingEngine() = EngineLifecycle(
        startAndAwaitReady = { calls += "engine" },
        stop = { calls += "engine stop" },
    )

    @Test
    fun startRequestsTheServiceThenTheEngineThenTheRadio() = runTest {
        val session = session()
        session.start(device, DiscoveryMode.BALANCED)
        assertEquals(listOf("keep-alive", "engine", "mode BALANCED", "radio start"), calls)
        assertTrue(session.running.value)
        assertTrue(session.keptAlive.value)
    }

    @Test
    fun theServiceSeesRunningBeforeItIsRequested() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        lateinit var session: MeshSession
        var runningAtRequest = false
        session = MeshSession(transport, null, { runningAtRequest = session.running.value; true }, scope)
        session.start(device, DiscoveryMode.BALANCED)
        assertTrue(runningAtRequest)
    }

    @Test
    fun aSecondStartIsIgnored() = runTest {
        val session = session()
        session.start(device, DiscoveryMode.BALANCED)
        calls.clear()
        session.start(device, DiscoveryMode.CONTINUOUS)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun stopEndsTheRadioAndEngineAndCanBeRepeated() = runTest {
        val session = session()
        session.start(device, DiscoveryMode.CONTINUOUS)
        calls.clear()
        session.stop()
        session.stop()
        assertEquals(listOf("radio stop", "engine stop", "mode BALANCED"), calls.take(3))
        assertFalse(session.running.value)
    }

    @Test
    fun theMeshCanStartAgainAfterStopping() = runTest {
        val session = session()
        session.start(device, DiscoveryMode.BALANCED)
        session.stop()
        calls.clear()
        session.start(device, DiscoveryMode.BALANCED)
        assertTrue("radio start" in calls)
        assertTrue(session.running.value)
    }

    @Test
    fun whenAndroidRefusesTheServiceTheMeshStillStartsButIsNotKeptAlive() = runTest {
        val session = session(keepAlive = false)
        session.start(device, DiscoveryMode.BALANCED)
        assertTrue(session.running.value)
        assertFalse(session.keptAlive.value)
    }

    @Test
    fun aServiceRestoringTheMeshDoesNotRequestItself() = runTest {
        val session = session()
        session.start(device, DiscoveryMode.CONTINUOUS, serviceRunning = true)
        assertEquals(listOf("engine", "mode CONTINUOUS", "radio start"), calls)
        assertTrue(session.keptAlive.value)
    }

    @Test
    fun aFailedEngineLeavesNothingRunning() = runTest {
        val engine = EngineLifecycle({ error("no collector") }, { calls += "engine stop" })
        val session = session(engine)
        val failure = runCatching { session.start(device, DiscoveryMode.BALANCED) }.exceptionOrNull()
        assertEquals("no collector", failure?.message)
        assertFalse("radio start" in calls)
        assertFalse(session.running.value)
    }

    @Test
    fun cancellingDuringStartupLeavesNothingRunning() = runTest {
        val ready = CompletableDeferred<Unit>()
        val session = session(EngineLifecycle({ ready.await() }, { calls += "engine stop" }))
        val job = launch { session.start(device, DiscoveryMode.BALANCED) }
        runCurrent()
        assertTrue(session.running.value)
        job.cancel()
        job.join()
        assertFalse("radio start" in calls)
        assertFalse(session.running.value)
    }

    @Test
    fun aRadioFailureIsReportedButNeverStopsTheMesh() = runTest {
        val session = session()
        session.start(device, DiscoveryMode.BALANCED)
        calls.clear()
        transport.emit(TransportEvent.Failure("Discovery failed"))
        assertEquals("Discovery failed", session.problem.value)
        assertTrue(session.running.value)
        assertFalse("radio stop" in calls)
    }

    @Test
    fun aNewConnectionClearsTheLastProblem() = runTest {
        val session = session()
        transport.emit(TransportEvent.Failure("Discovery failed"))
        transport.emit(TransportEvent.PeerConnected(PeerInfo("e", "d", "Phone B", PeerState.CONNECTED)))
        assertNull(session.problem.value)
    }

    @Test
    fun theServiceReportsWhetherItReallyRuns() = runTest {
        val session = session()
        session.start(device, DiscoveryMode.BALANCED)
        session.keepAliveChanged(false)
        assertFalse(session.keptAlive.value)
        session.keepAliveChanged(true)
        assertTrue(session.keptAlive.value)
    }

    @Test
    fun statusTextShowsLinksApprovalsAndTheLastProblem() {
        assertEquals("Looking for nearby phones", meshStatusText(0, 0, null))
        assertEquals("1 phone connected", meshStatusText(1, 0, null))
        assertEquals("3 phones connected", meshStatusText(3, 0, null))
        assertEquals("1 phone connected · Open the app to approve 2", meshStatusText(1, 2, null))
        assertEquals(
            "2 phones connected · Last problem: Discovery failed",
            meshStatusText(2, 0, "Discovery failed"),
        )
    }

    private class RecordingTransport(private val calls: MutableList<String>) : MeshTransport {
        private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 8)
        override val peers: StateFlow<List<PeerInfo>> = MutableStateFlow(emptyList())
        override val events: SharedFlow<TransportEvent> = _events

        fun emit(event: TransportEvent) {
            assertTrue(_events.tryEmit(event))
        }

        override fun start(localDeviceId: String, displayName: String) {
            calls += "radio start"
        }

        override fun stop() {
            calls += "radio stop"
        }

        override fun setDiscoveryMode(mode: DiscoveryMode) {
            calls += "mode $mode"
        }

        override fun respondToConnection(endpointId: String, accept: Boolean) = Unit
        override suspend fun send(endpointId: String, packet: Packet): SendResult = SendResult.Failed("unused")
        override fun setDebugAllowlist(deviceIds: Set<String>?) = Unit
    }
}
