package com.slackers.emergencymesh.ui

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * M2's adapter to M3's engine lifecycle, not an engine implementation.
 * [startAndAwaitReady] MUST return only after the transport-events collector is
 * subscribed (for example, an undispatched collector or M3's readiness signal).
 * Merely launching a collector for later execution does not satisfy this API.
 * [stop] must be non-blocking and idempotent; restart must be supported.
 */
class EngineLifecycle(
    private val startAndAwaitReady: suspend () -> Unit,
    val stop: () -> Unit,
) {
    suspend fun startBeforeTransport(startTransport: () -> Unit) {
        try {
            startAndAwaitReady()
            currentCoroutineContext().ensureActive()
            startTransport()
        } catch (failure: Exception) {
            runCatching(stop)
            throw failure
        }
    }
}
