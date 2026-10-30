package com.slackers.emergencymesh.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineLifecycleTest {
    @Test
    fun radioWaitsUntilEngineCollectorIsReady() = runTest {
        val ready = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val lifecycle = EngineLifecycle(
            startAndAwaitReady = { events += "subscribing"; ready.await(); events += "ready" },
            stop = {},
        )
        val job = launch { lifecycle.startBeforeTransport { events += "radio" } }
        runCurrent()
        assertEquals(listOf("subscribing"), events)
        ready.complete(Unit)
        job.join()
        assertEquals(listOf("subscribing", "ready", "radio"), events)
    }

    @Test
    fun backgroundCancellationDuringStartupNeverStartsRadio() = runTest {
        val ready = CompletableDeferred<Unit>()
        var radioStarted = false
        var engineStopped = false
        val lifecycle = EngineLifecycle({ ready.await() }, { engineStopped = true })
        val job = launch { lifecycle.startBeforeTransport { radioStarted = true } }
        runCurrent()
        job.cancel()
        job.join()
        assertFalse(radioStarted)
        assertTrue(engineStopped)
    }

    @Test
    fun failedEngineReadinessIsVisibleAndDoesNotStartRadio() = runTest {
        var radioStarted = false
        var engineStopped = false
        val lifecycle = EngineLifecycle(
            { throw IllegalStateException("subscription failed") },
            { engineStopped = true },
        )
        val result = runCatching { lifecycle.startBeforeTransport { radioStarted = true } }
        assertEquals("subscription failed", result.exceptionOrNull()?.message)
        assertFalse(radioStarted)
        assertTrue(engineStopped)
    }
}
