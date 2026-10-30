package com.slackers.emergencymesh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Where received contact cards are stored. */
interface ContactBook {
    suspend fun find(deviceId: String): ContactCard?
    suspend fun save(card: ContactCard)
}

/** A known device sent different keys. The stored keys were kept. */
data class ContactKeyWarning(val deviceId: String, val displayName: String)

/**
 * Swaps public contact cards whenever two phones connect, so phones with no internet can enroll
 * each other. Received cards stay unverified until people compare fingerprints. This runs beside
 * the message engine, which must ignore [PacketType.CONTACT_CARD].
 */
class ContactCardService(
    private val transport: MeshTransport,
    private val security: MessageSecurity,
    private val contacts: ContactBook,
    private val newPacketId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _keyWarnings = MutableStateFlow<List<ContactKeyWarning>>(emptyList())

    /** Show these to the user. A changed key can mean a reinstall or an impersonator. */
    val keyWarnings: StateFlow<List<ContactKeyWarning>> = _keyWarnings.asStateFlow()

    /** Subscribes before returning, so call this before starting the transport. */
    fun start(scope: CoroutineScope): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.PeerConnected ->
                        launch { sendCard(event.peer.endpointId) }
                    is TransportEvent.PacketReceived ->
                        if (event.packet.type == PacketType.CONTACT_CARD) {
                            receive(event.fromEndpointId, event.packet, this)
                        }
                    else -> Unit
                }
            }
        }

    private suspend fun sendCard(endpointId: String) {
        repeat(SEND_ATTEMPTS) { attempt ->
            try {
                val packet = ContactExchange.buildPacket(security, newPacketId(), now())
                if (transport.send(endpointId, packet) is SendResult.TransportComplete) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Contact card couldn't be prepared: ${e.message}")
                return
            }
            if (attempt < SEND_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }
        log("Contact card wasn't sent to $endpointId")
    }

    private suspend fun receive(endpointId: String, packet: Packet, scope: CoroutineScope) {
        val peer = transport.peers.value.firstOrNull { it.endpointId == endpointId }
        if (peer == null) {
            log("Dropped a contact card from unknown endpoint $endpointId")
            return
        }
        val card = try {
            ContactExchange.readPacket(security, packet, peer.deviceId)
        } catch (e: SecurityException) {
            log("Dropped a contact card from ${peer.deviceId}: ${e.message}")
            return
        }
        val known = contacts.find(card.deviceId)
        when (ContactExchange.compare(known, card)) {
            ContactCardChange.NEW -> {
                contacts.save(card)
                log("Saved unverified contact ${card.deviceId}")
                // Covers a card of ours that was lost, so an exchange can't stay one-sided.
                scope.launch { sendCard(endpointId) }
            }
            ContactCardChange.UNCHANGED -> Unit
            ContactCardChange.KEYS_CHANGED -> warn(card)
        }
    }

    private fun warn(card: ContactCard) {
        log("Kept stored keys for ${card.deviceId}, because it sent different keys")
        val warning = ContactKeyWarning(card.deviceId, card.displayName)
        if (warning !in _keyWarnings.value) _keyWarnings.value += warning
    }

    private companion object {
        const val SEND_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 2_000L
    }
}
