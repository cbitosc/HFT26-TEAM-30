package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.MeshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout

/**
 * The engine gets this transport view; the UI keeps the original transport.
 * onSubscription runs after the engine is registered with the real SharedFlow,
 * so startup does not guess whether a launched collector has run yet.
 * Start/stop operations are serialized by the app's connection controls.
 */
class EngineEventGate(transport: MeshTransport) : MeshTransport by transport {
    private var ready = CompletableDeferred<Unit>()

    override val events = transport.events.onSubscription { ready.complete(Unit) }

    suspend fun startAndAwaitSubscription(startEngine: () -> Unit) {
        ready = CompletableDeferred()
        startEngine()
        withTimeout(5_000) { ready.await() }
    }
}
