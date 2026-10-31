package com.slackers.emergencymesh.engine

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MessageRepository
import com.slackers.emergencymesh.core.MessageSecurity
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.Packet
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.MAX_TTL
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.TransportEvent
import com.slackers.emergencymesh.data.ContactEntity
import com.slackers.emergencymesh.data.EmergencyMeshDao
import com.slackers.emergencymesh.data.MessageEntity
import com.slackers.emergencymesh.data.QueuedPacketEntity
import com.slackers.emergencymesh.data.SeenPacketEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Room-backed repository and store-carry-forward engine for M3. */
class MessageRepositoryImpl(
    private val dao: EmergencyMeshDao,
    private val transport: MeshTransport,
    private val security: MessageSecurity,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MessageRepository, MessageStatusTimeline {
    private val localCard = security.localContactCard()
    private var eventJob: Job? = null
    private var engineJob: Job? = null
    private var engineScope = scope
    private val peerLocks = mutableMapOf<String, Mutex>()

    override val contacts: Flow<List<ContactCard>> = dao.observeContacts().map { rows -> rows.map(::toCard) }

    override val sosAlerts: Flow<List<ChatMessage>> = dao.observeSosMessages().map { rows ->
        rows.mapNotNull(::toChatMessage)
    }

    override fun conversation(deviceId: String): Flow<List<ChatMessage>> =
        dao.observeConversation(deviceId).map { rows -> rows.mapNotNull(::toChatMessage) }

    override fun observeStatusTimes(messageId: String): Flow<MessageStatusTimes?> =
        dao.observeMessage(messageId).map { row ->
            row?.let {
                MessageStatusTimes(
                    sentAtMillis = it.sentAtMillis,
                    relayedAtMillis = it.relayedAtMillis,
                    deliveredAtMillis = it.deliveredAtMillis,
                )
            }
        }

    /**
     * Subscribe before returning so callers can safely start the radio immediately afterward.
     * UNDISPATCHED runs the collector through SharedFlow subscription before its first suspension.
     */
    fun start() = startInternal()

    /** Suspendable adapter for EngineLifecycle; subscription is already ready on return. */
    suspend fun startAndAwaitReady() = startInternal()

    @Synchronized
    private fun startInternal() {
        if (eventJob?.isActive == true) return
        eventJob = workers().launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.PeerConnected -> workers().launch { dispatchTo(event.peer) }
                    is TransportEvent.PacketReceived -> handleIncoming(event.fromEndpointId, event.packet)
                    else -> Unit
                }
            }
        }
        transport.peers.value
            .filter { it.state == PeerState.CONNECTED }
            .forEach { peer -> engineScope.launch { dispatchTo(peer) } }
    }

    @Synchronized
    fun stop() {
        eventJob?.cancel()
        eventJob = null
        engineJob?.cancel()
        engineJob = null
        engineScope = scope
    }

    override suspend fun sendDirect(recipientDeviceId: String, text: String, priority: Priority): String {
        require(text.length <= MAX_TEXT_CHARS) { "Message is too long" }
        val recipient = dao.contact(recipientDeviceId)?.let(::toCard)
            ?: throw IllegalArgumentException("Enroll this contact before sending")
        require(recipient.verified) { "Verify this contact before sending" }

        val id = UUID.randomUUID().toString()
        val created = nowMillis()
        val packet = seal(
            type = PacketType.MESSAGE,
            id = id,
            recipientId = recipient.deviceId,
            refMessageId = null,
            priority = priority,
            createdAtMillis = created,
            body = MessageBody(text),
            broadcast = false,
            recipient = recipient,
        )
        val storedBody = PacketCodec.encodeBody(MessageBody(text))
        dao.saveOutgoing(
            message = MessageEntity(
                messageId = id,
                conversationDeviceId = recipientDeviceId,
                senderId = localCard.deviceId,
                outgoing = true,
                type = PacketType.MESSAGE.name,
                priority = priority.ordinal,
                body = storedBody,
                createdAtMillis = created,
                status = MessageStatus.PENDING.name,
                hopCount = 0,
            ),
            packet = packet.toQueue(
                sourceDeviceId = null,
                firstSeenAtMillis = created,
                queuedAtMillis = created,
                outgoing = true,
            ),
        )
        dispatchConnectedPeers()
        return id
    }

    override suspend fun sendSos(body: MessageBody): String {
        require(body.text.length <= MAX_TEXT_CHARS) { "SOS text is too long" }
        val id = UUID.randomUUID().toString()
        val created = nowMillis()
        val packet = seal(
            type = PacketType.SOS,
            id = id,
            recipientId = null,
            refMessageId = null,
            priority = Priority.P0_SOS,
            createdAtMillis = created,
            body = body,
            broadcast = true,
            recipient = null,
        )
        dao.saveOutgoing(
            message = MessageEntity(
                messageId = id,
                conversationDeviceId = null,
                senderId = localCard.deviceId,
                outgoing = true,
                type = PacketType.SOS.name,
                priority = Priority.P0_SOS.ordinal,
                body = PacketCodec.encodeBody(body),
                createdAtMillis = created,
                status = MessageStatus.PENDING.name,
                hopCount = 0,
            ),
            packet = packet.toQueue(null, created, created, outgoing = true),
        )
        dispatchConnectedPeers()
        return id
    }

    override suspend fun saveContact(card: ContactCard) {
        dao.putContact(
            ContactEntity(
                deviceId = card.deviceId,
                displayName = card.displayName,
                signingPublicKey = card.signingPublicKey,
                encryptionPublicKey = card.encryptionPublicKey,
                verified = card.verified,
            ),
        )
    }

    private fun dispatchConnectedPeers() {
        transport.peers.value.filter { it.state == PeerState.CONNECTED }.forEach { peer ->
            workers().launch { dispatchTo(peer) }
        }
    }

    private suspend fun dispatchTo(peer: PeerInfo) {
        if (peer.state != PeerState.CONNECTED || peer.deviceId == localCard.deviceId) return
        val lock = synchronized(peerLocks) { peerLocks.getOrPut(peer.deviceId) { Mutex() } }
        lock.withLock {
            var dispatched = 0
            while (dispatched < DISPATCH_BATCH_SIZE) {
                val now = nowMillis()
                dao.pruneExpiredPackets(now)
                dao.pruneOrphanedPeerReceipts()
                // Re-query after every transfer so newly queued P0 SOS work jumps ahead of
                // the remaining backlog instead of waiting for a stale batch to drain.
                val queued = dao.dueOutgoingForPeer(peer.deviceId, now, limit = 1).firstOrNull() ?: break
                val prior = dao.receipt(queued.packetId, peer.deviceId)
                val attempt = (prior?.attemptCount ?: 0) + 1
                val inFlightReceipt = com.slackers.emergencymesh.data.PeerReceiptEntity(
                    packetId = queued.packetId,
                    peerDeviceId = peer.deviceId,
                    acknowledgedAtMillis = null,
                    attemptCount = attempt,
                    nextAttemptAtMillis = now + retryDelayMillis(attempt),
                )
                dao.putPeerReceipt(inFlightReceipt)
                val packet = runCatching { PacketCodec.decode(queued.encodedPacket) }.getOrNull()
                if (packet == null) {
                    dao.deleteQueuedPacket(queued.packetId)
                    continue
                }
                val sendResult = transport.send(peer.endpointId, packet)
                dispatched++
                // ACK packets have no app-level ACK of their own. A successful byte transfer is
                // therefore terminal for this immediate peer; failed sends get at most three tries.
                val terminalAck = packet.type.isAck &&
                    (sendResult == SendResult.TransportComplete || attempt >= MAX_ACK_ATTEMPTS)
                if (terminalAck) {
                    dao.putPeerReceipt(inFlightReceipt.copy(acknowledgedAtMillis = nowMillis()))
                }
                if (!terminalAck) {
                    // A transport-complete result for ordinary messages is deliberately not an
                    // application receipt; those remain retryable until the signed ACK arrives.
                    workers().launch {
                        delay(retryDelayMillis(attempt))
                        val reconnectedPeer = transport.peers.value.firstOrNull {
                            it.deviceId == peer.deviceId && it.state == PeerState.CONNECTED
                        }
                        if (reconnectedPeer != null) dispatchTo(reconnectedPeer)
                    }
                }
            }
        }
    }

    private suspend fun handleIncoming(fromEndpointId: String, packet: Packet) {
        // ContactCardService owns direct card exchange and enrollment. The relay engine must
        // never retain, acknowledge, forward, or display a contact-card packet.
        if (packet.type == PacketType.CONTACT_CARD) return
        if (!isValidHopState(packet)) return
        val source = transport.peers.value.firstOrNull { it.endpointId == fromEndpointId } ?: return
        val sender = dao.contact(packet.senderId)?.let(::toCard) ?: return
        if (!sender.verified || !security.verify(sender, PacketCodec.signedBytes(packet), packet.signature)) return

        val now = nowMillis()
        val sourceReceipt = dao.receipt(packet.id, source.deviceId)
        dao.putPeerReceipt(
            com.slackers.emergencymesh.data.PeerReceiptEntity(
                packetId = packet.id,
                peerDeviceId = source.deviceId,
                acknowledgedAtMillis = now,
                attemptCount = sourceReceipt?.attemptCount ?: 0,
                nextAttemptAtMillis = now,
            ),
        )
        dao.pruneSeen(now)
        val inserted = dao.recordSeen(
            SeenPacketEntity(
                packetId = packet.id,
                firstSeenAtMillis = now,
                expiresAtMillis = now + packet.retentionSeconds * 1_000L,
            ),
        )
        val duplicate = inserted == -1L
        dao.pruneExpiredPackets(now)

        if (packet.type.isAck) {
            handleAck(packet, source, now)
            if (!duplicate && packet.ttl > 0) enqueueForward(packet.forwarded(), source.deviceId, now)
            dao.pruneOrphanedPeerReceipts()
            return
        }

        if (!duplicate) {
            if (isForThisDevice(packet)) {
                val plain = try {
                    val aad = PacketCodec.associatedData(packet)
                    if (packet.type == PacketType.SOS) {
                        security.decryptBroadcast(packet.encryptedBody, aad)
                    } else {
                        security.decryptDirect(packet.encryptedBody, aad)
                    }
                } catch (_: SecurityException) {
                    return
                }
                val body = runCatching { PacketCodec.decodeBody(plain) }.getOrNull() ?: return
                dao.putMessage(
                    MessageEntity(
                        messageId = packet.id,
                        conversationDeviceId = if (packet.type == PacketType.SOS) null else packet.senderId,
                        senderId = packet.senderId,
                        outgoing = false,
                        type = packet.type.name,
                        priority = packet.priority.ordinal,
                        body = plain,
                        createdAtMillis = packet.createdAtMillis,
                        status = if (packet.type == PacketType.MESSAGE) MessageStatus.DELIVERED.name else MessageStatus.PENDING.name,
                        hopCount = packet.hopCount,
                        deliveredAtMillis = if (packet.type == PacketType.MESSAGE) now else null,
                    ),
                )
            }
            if (packet.ttl > 0 && (packet.type == PacketType.SOS || !isForThisDevice(packet))) {
                enqueueForward(packet.forwarded(), source.deviceId, now)
            }
        }

        // Repeat ACKs for valid duplicates so a lost receipt can recover; never ACK an ACK.
        enqueueAck(PacketType.ACK_SENT, packet, now)
        if (isForThisDevice(packet) && packet.type == PacketType.MESSAGE) {
            enqueueAck(PacketType.ACK_DELIVERED, packet, now)
        }
        dao.pruneOrphanedPeerReceipts()
    }

    private suspend fun handleAck(packet: Packet, source: PeerInfo, now: Long) {
        val referencedId = packet.refMessageId ?: return
        dao.acknowledgePacketForPeer(referencedId, packet.senderId, now)

        val message = dao.message(referencedId)
        if (message != null && message.outgoing) {
            val newStatus = when (packet.type) {
                PacketType.ACK_SENT -> MessageStatus.SENT
                PacketType.ACK_RELAYED -> MessageStatus.RELAYED
                PacketType.ACK_DELIVERED -> {
                    if (packet.senderId == message.conversationDeviceId) MessageStatus.DELIVERED else null
                }
                else -> null
            }
            if (newStatus != null) dao.advanceMessageStatus(referencedId, newStatus, now)
        }

        // A relay confirms onward progress to the original sender after a downstream ACK.
        if (packet.type == PacketType.ACK_SENT) {
            val original = dao.queuedPacket(referencedId)?.let {
                runCatching { PacketCodec.decode(it.encodedPacket) }.getOrNull()
            }
            if (original != null && original.senderId != localCard.deviceId && original.senderId != source.deviceId) {
                enqueueAck(PacketType.ACK_RELAYED, original, now)
            }
        }
    }

    private suspend fun enqueueAck(type: PacketType, original: Packet, now: Long) {
        val recipient = dao.contact(original.senderId)?.let(::toCard) ?: return
        if (!recipient.verified) return
        val ack = seal(
            type = type,
            id = UUID.randomUUID().toString(),
            recipientId = recipient.deviceId,
            refMessageId = original.id,
            priority = Priority.P1_EMERGENCY,
            createdAtMillis = now,
            body = MessageBody(""),
            broadcast = false,
            recipient = recipient,
        )
        enqueueForward(ack, sourceDeviceId = null, now = now)
    }

    private suspend fun enqueueForward(packet: Packet, sourceDeviceId: String?, now: Long) {
        dao.putQueuedPacket(
            packet.toQueue(
                sourceDeviceId = sourceDeviceId,
                firstSeenAtMillis = now,
                queuedAtMillis = now,
                outgoing = true,
            ),
        )
        dispatchConnectedPeers()
    }

    private fun isForThisDevice(packet: Packet): Boolean =
        packet.type == PacketType.SOS || packet.recipientId == localCard.deviceId

    private fun isValidHopState(packet: Packet): Boolean =
        packet.ttl in 0..packet.initialTtl &&
            packet.hopCount in 0..packet.initialTtl &&
            packet.ttl + packet.hopCount == packet.initialTtl &&
            packet.initialTtl <= MAX_TTL &&
            packet.retentionSeconds > 0 &&
            (!packet.type.isAck || packet.refMessageId != null)

    private suspend fun seal(
        type: PacketType,
        id: String,
        recipientId: String?,
        refMessageId: String?,
        priority: Priority,
        createdAtMillis: Long,
        body: MessageBody,
        broadcast: Boolean,
        recipient: ContactCard?,
    ): Packet {
        val header = Packet(
            type = type,
            id = id,
            senderId = localCard.deviceId,
            recipientId = recipientId,
            refMessageId = refMessageId,
            priority = priority,
            createdAtMillis = createdAtMillis,
            initialTtl = priority.defaultTtl,
            retentionSeconds = priority.retentionSeconds,
            ttl = priority.defaultTtl,
            hopCount = 0,
            encryptedBody = byteArrayOf(),
            signature = byteArrayOf(),
        )
        val aad = PacketCodec.associatedData(header)
        val plaintext = PacketCodec.encodeBody(body)
        val ciphertext = if (broadcast) {
            security.encryptBroadcast(plaintext, aad)
        } else {
            security.encryptDirect(plaintext, requireNotNull(recipient), aad)
        }
        val unsigned = header.copy(encryptedBody = ciphertext)
        return unsigned.copy(signature = security.sign(PacketCodec.signedBytes(unsigned)))
    }

    private fun Packet.forwarded(): Packet = copy(ttl = ttl - 1, hopCount = hopCount + 1)

    private fun Packet.toQueue(
        sourceDeviceId: String?,
        firstSeenAtMillis: Long,
        queuedAtMillis: Long,
        outgoing: Boolean,
    ) = QueuedPacketEntity(
        packetId = id,
        messageId = refMessageId ?: id,
        recipientDeviceId = recipientId,
        encodedPacket = PacketCodec.encode(this),
        priority = priority.ordinal,
        receivedFromDeviceId = sourceDeviceId,
        expiresAtMillis = firstSeenAtMillis + retentionSeconds * 1_000L,
        firstSeenAtMillis = firstSeenAtMillis,
        queuedAtMillis = queuedAtMillis,
        nextAttemptAtMillis = queuedAtMillis,
        attemptCount = 0,
        outgoing = outgoing,
    )

    private fun toCard(row: ContactEntity) = ContactCard(
        deviceId = row.deviceId,
        displayName = row.displayName,
        signingPublicKey = row.signingPublicKey,
        encryptionPublicKey = row.encryptionPublicKey,
        verified = row.verified,
    )

    private fun toChatMessage(row: MessageEntity): ChatMessage? {
        val body = runCatching { PacketCodec.decodeBody(row.body) }.getOrNull() ?: return null
        return ChatMessage(
            messageId = row.messageId,
            conversationDeviceId = row.conversationDeviceId,
            senderId = row.senderId,
            outgoing = row.outgoing,
            type = runCatching { PacketType.valueOf(row.type) }.getOrNull() ?: return null,
            priority = Priority.entries.getOrNull(row.priority) ?: Priority.P2_REGULAR,
            body = body,
            createdAtMillis = row.createdAtMillis,
            status = statusOf(row.status),
            hopCount = row.hopCount,
        )
    }

    private fun statusOf(value: String) =
        runCatching { MessageStatus.valueOf(value) }.getOrDefault(MessageStatus.PENDING)

    private fun retryDelayMillis(attempt: Int): Long =
        (INITIAL_RETRY_MS shl (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_RETRY_MS)

    @Synchronized
    private fun workers(): CoroutineScope {
        if (engineJob?.isActive != true) {
            engineJob = SupervisorJob(scope.coroutineContext[Job])
            engineScope = CoroutineScope(scope.coroutineContext + engineJob!!)
        }
        return engineScope
    }

    private companion object {
        const val DISPATCH_BATCH_SIZE = 32
        const val MAX_ACK_ATTEMPTS = 3
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 60_000L
        const val MAX_BACKOFF_SHIFT = 6
    }
}
