package com.slackers.emergencymesh.engine

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.ContactExchange
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageSecurity
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.core.SendResult
import com.slackers.emergencymesh.core.SpokenQuestion
import com.slackers.emergencymesh.data.ContactEntity
import com.slackers.emergencymesh.data.EmergencyMeshDao
import com.slackers.emergencymesh.data.MessageEntity
import com.slackers.emergencymesh.data.PeerReceiptEntity
import com.slackers.emergencymesh.data.QueuedPacketEntity
import com.slackers.emergencymesh.data.SeenPacketEntity
import com.slackers.emergencymesh.transport.InMemoryMesh
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayEngineInMemoryMeshTest {
    @Test
    fun aToCMessageRelaysThroughBWithoutEchoingToA() = runTest {
        val mesh = InMemoryMesh()
        val transportA = mesh.phone("A")
        val transportB = mesh.phone("B")
        val transportC = mesh.phone("C")
        mesh.link("A", "B")
        mesh.link("B", "C")

        val cardA = contact("A")
        val cardB = contact("B")
        val cardC = contact("C")
        val daoA = InMemoryEmergencyMeshDao()
        val daoB = InMemoryEmergencyMeshDao()
        val daoC = InMemoryEmergencyMeshDao()
        val securityA = TestMessageSecurity(cardA)
        val securityB = TestMessageSecurity(cardB)
        val securityC = TestMessageSecurity(cardC)
        val repoA = MessageRepositoryImpl(daoA, transportA, securityA, this)
        val repoB = MessageRepositoryImpl(daoB, transportB, securityB, this)
        val repoC = MessageRepositoryImpl(daoC, transportC, securityC, this)

        try {
            listOf(repoA, repoB, repoC).forEach {
                it.startAndAwaitReady()
                it.startAndAwaitReady()
            }
            repoA.saveContact(cardB)
            repoA.saveContact(cardC)
            repoB.saveContact(cardA)
            repoB.saveContact(cardC)
            repoC.saveContact(cardA)
            repoC.saveContact(cardB)

            val messageId = repoA.sendDirect("C", "Relay test", Priority.P2_REGULAR)
            runCurrent()

            assertEquals(MessageStatus.DELIVERED.name, daoA.message(messageId)?.status)
            val statusTimes = requireNotNull(repoA.observeStatusTimes(messageId).first())
            assertNotNull(statusTimes.sentAtMillis)
            assertNotNull(statusTimes.relayedAtMillis)
            assertNotNull(statusTimes.deliveredAtMillis)
            val received = requireNotNull(daoC.message(messageId))
            assertEquals("Relay test", PacketCodec.decodeBody(received.body).text)
            assertNotNull(received.deliveredAtMillis)
            assertEquals(0, securityB.directDecryptCount)
            assertNull("The relay should not create a displayed-message row", daoB.message(messageId))

            assertTrue(
                mesh.sentLog.any {
                    it.fromDeviceId == "B" && it.toDeviceId == "C" &&
                        it.packet.id == messageId && it.packet.priority == Priority.P2_REGULAR &&
                        it.packet.hopCount == 1 && it.packet.ttl == 4
                },
            )
            assertNull("Sender removes the message after final delivery ACK", daoA.queuedPacket(messageId))
            assertNull("Relay removes the retained message after final delivery ACK", daoB.queuedPacket(messageId))
            assertFalse(
                "B must not echo A's packet back to A",
                mesh.sentLog.any {
                    it.fromDeviceId == "B" && it.toDeviceId == "A" && it.packet.id == messageId
                },
            )
        } finally {
            listOf(repoA, repoB, repoC).forEach {
                it.stop()
                it.stop()
            }
        }
    }

    @Test
    fun contactCardPacketsAreIgnoredByTheRelayEngine() = runTest {
        val mesh = InMemoryMesh()
        val transportA = mesh.phone("A")
        val transportB = mesh.phone("B")
        mesh.link("A", "B")

        val cardA = contact("A")
        val cardB = contact("B")
        val daoB = InMemoryEmergencyMeshDao()
        val repoA = MessageRepositoryImpl(InMemoryEmergencyMeshDao(), transportA, TestMessageSecurity(cardA), this)
        val repoB = MessageRepositoryImpl(daoB, transportB, TestMessageSecurity(cardB), this)

        try {
            repoA.startAndAwaitReady()
            repoB.start()
            repoB.saveContact(cardA)
            val packet = ContactExchange.buildPacket(TestMessageSecurity(cardA), "contact-A", 1L)
            transportA.send(transportB.peers.value.single().endpointId, packet)
            runCurrent()

            assertFalse(daoB.hasSeen(packet.id))
            assertNull(daoB.queuedPacket(packet.id))
            assertNull(daoB.message(packet.id))
            assertFalse(mesh.sentLog.any { it.fromDeviceId == "B" })
        } finally {
            repoA.stop()
            repoA.stop()
            repoB.stop()
            repoB.stop()
        }
    }

    @Test
    fun newlyQueuedSosJumpsAheadOfTheRemainingBacklog() = runTest {
        val mesh = InMemoryMesh()
        val underlyingA = mesh.phone("A")
        val transportB = mesh.phone("B")

        val cardA = contact("A")
        val cardB = contact("B")
        val gatedTransportA = PauseFirstSendTransport(underlyingA)
        val repoA = MessageRepositoryImpl(InMemoryEmergencyMeshDao(), gatedTransportA, TestMessageSecurity(cardA), this)
        val repoB = MessageRepositoryImpl(InMemoryEmergencyMeshDao(), transportB, TestMessageSecurity(cardB), this)

        try {
            repoA.startAndAwaitReady()
            repoB.startAndAwaitReady()
            repoA.saveContact(cardB)
            repoB.saveContact(cardA)

            val firstRegularId = repoA.sendDirect("B", "first", Priority.P2_REGULAR)
            repoA.sendDirect("B", "second", Priority.P2_REGULAR)
            repoA.sendDirect("B", "third", Priority.P2_REGULAR)
            // Queue the regular backlog while offline. Connecting snapshots that backlog into
            // one dispatch batch, then the in-flight send is paused while SOS is added.
            mesh.link("A", "B")
            runCurrent()
            assertTrue(gatedTransportA.firstSendStarted.isCompleted)

            repoA.sendSos(MessageBody("urgent"))
            gatedTransportA.releaseFirstSend.complete(Unit)
            runCurrent()

            val outgoingTypes = mesh.sentLog
                .filter { it.fromDeviceId == "A" && it.toDeviceId == "B" }
                .map { it.packet }
                .filter { it.type == PacketType.MESSAGE || it.type == PacketType.SOS }
            assertEquals(firstRegularId, outgoingTypes.first().id)
            assertEquals(PacketType.SOS, outgoingTypes[1].type)
        } finally {
            repoA.stop()
            repoB.stop()
        }
    }

    @Test
    fun ackPacketsStopRetryingAfterSuccessfulTransfer() = runTest {
        val mesh = InMemoryMesh()
        val transportA = mesh.phone("A")
        val transportB = mesh.phone("B")
        mesh.link("A", "B")

        val cardA = contact("A")
        val cardB = contact("B")
        val daoB = InMemoryEmergencyMeshDao()
        val repoA = MessageRepositoryImpl(InMemoryEmergencyMeshDao(), transportA, TestMessageSecurity(cardA), this)
        val repoB = MessageRepositoryImpl(daoB, transportB, TestMessageSecurity(cardB), this)

        try {
            repoA.startAndAwaitReady()
            repoB.startAndAwaitReady()
            repoA.saveContact(cardB)
            repoB.saveContact(cardA)
            val messageId = repoA.sendDirect("B", "ack receipt test", Priority.P2_REGULAR)
            runCurrent()

            val ack = daoB.queuedPackets()
                .mapNotNull { runCatching { PacketCodec.decode(it.encodedPacket) }.getOrNull() }
                .first { it.type == PacketType.ACK_SENT && it.refMessageId == messageId }
            assertNotNull(daoB.receipt(ack.id, "A")?.acknowledgedAtMillis)
            assertEquals(
                emptyList<QueuedPacketEntity>(),
                daoB.dueOutgoingForPeer("A", daoB.queuedPacket(ack.id)!!.expiresAtMillis - 1L, 32)
                    .filter { it.packetId == ack.id },
            )
            assertEquals(1, mesh.sentLog.count { it.fromDeviceId == "B" && it.toDeviceId == "A" && it.packet.id == ack.id })
        } finally {
            repoA.stop()
            repoB.stop()
        }
    }

    @Test
    fun failedAckTransfersStopAfterThreeAttempts() = runTest {
        val mesh = InMemoryMesh()
        val transportA = mesh.phone("A")
        val underlyingB = mesh.phone("B")
        mesh.link("A", "B")

        val cardA = contact("A")
        val cardB = contact("B")
        val daoB = InMemoryEmergencyMeshDao()
        val failingTransportB = FailAckTransport(underlyingB)
        val nowMillis = { 1_000L + testScheduler.currentTime }
        val repoA = MessageRepositoryImpl(InMemoryEmergencyMeshDao(), transportA, TestMessageSecurity(cardA), this, nowMillis)
        val repoB = MessageRepositoryImpl(daoB, failingTransportB, TestMessageSecurity(cardB), this, nowMillis)

        try {
            repoA.startAndAwaitReady()
            repoB.startAndAwaitReady()
            repoA.saveContact(cardB)
            repoB.saveContact(cardA)
            repoA.sendDirect("B", "bounded ack retry test", Priority.P2_REGULAR)
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            val acknowledgements = daoB.queuedPackets()
                .mapNotNull { runCatching { PacketCodec.decode(it.encodedPacket) }.getOrNull() }
                .filter { it.type.isAck && it.recipientId == "A" }
            assertTrue(acknowledgements.isNotEmpty())
            for (ack in acknowledgements) {
                assertEquals(3, failingTransportB.attemptsByPacket[ack.id])
                val receipt = requireNotNull(daoB.receipt(ack.id, "A"))
                assertEquals(3, receipt.attemptCount)
                assertNotNull(receipt.acknowledgedAtMillis)
            }
        } finally {
            repoA.stop()
            repoB.stop()
        }
    }

    @Test
    fun incomingPacketIsHandledWhilePeerDispatchIsWaitingOnSlowSend() = runTest {
        val mesh = InMemoryMesh()
        val underlyingA = mesh.phone("A")
        val transportB = mesh.phone("B")
        val cardA = contact("A")
        val cardB = contact("B")
        val daoA = InMemoryEmergencyMeshDao()
        val gatedTransportA = PauseFirstSendTransport(underlyingA)
        val repoA = MessageRepositoryImpl(daoA, gatedTransportA, TestMessageSecurity(cardA), this)
        val repoB = MessageRepositoryImpl(InMemoryEmergencyMeshDao(), transportB, TestMessageSecurity(cardB), this)

        try {
            repoA.startAndAwaitReady()
            repoB.startAndAwaitReady()
            repoA.saveContact(cardB)
            repoB.saveContact(cardA)
            repoA.sendDirect("B", "outgoing backlog", Priority.P2_REGULAR)
            mesh.link("A", "B")
            runCurrent()
            assertTrue(gatedTransportA.firstSendStarted.isCompleted)

            val incomingId = repoB.sendDirect("A", "arrives during slow send", Priority.P2_REGULAR)
            runCurrent()
            assertEquals("B", daoA.message(incomingId)?.senderId)

            gatedTransportA.releaseFirstSend.complete(Unit)
            runCurrent()
        } finally {
            if (!gatedTransportA.releaseFirstSend.isCompleted) gatedTransportA.releaseFirstSend.complete(Unit)
            repoA.stop()
            repoB.stop()
        }
    }

    /** Three phones in a line, so every call from A to C has to cross relay B. */
    private inner class CallOutLine(
        scope: TestScope,
        cAcceptsCalls: Boolean = true,
        cClock: () -> Long = System::currentTimeMillis,
        cTrustsA: Boolean = true,
    ) {
        val mesh = InMemoryMesh()
        private val transportA = mesh.phone("A")
        private val transportB = mesh.phone("B")
        private val transportC = mesh.phone("C")
        val daoA = InMemoryEmergencyMeshDao()
        val daoB = InMemoryEmergencyMeshDao()
        val daoC = InMemoryEmergencyMeshDao()
        val securityB = TestMessageSecurity(contact("B"))
        val repoA = MessageRepositoryImpl(daoA, transportA, TestMessageSecurity(contact("A")), scope)
        val repoB = MessageRepositoryImpl(daoB, transportB, securityB, scope, acceptsCallOut = { true })
        val repoC = MessageRepositoryImpl(
            daoC, transportC, TestMessageSecurity(contact("C")), scope,
            nowMillis = cClock, acceptsCallOut = { cAcceptsCalls },
        )
        val ringsAtB = mutableListOf<String>()
        val ringsAtC = mutableListOf<String>()
        val confirmedAtA = mutableListOf<String>()
        val questionsAtC = mutableListOf<SpokenQuestion>()
        val questionsAtB = mutableListOf<SpokenQuestion>()
        private val trustedA = contact("A").copy(verified = cTrustsA)

        suspend fun start(scope: TestScope) {
            mesh.link("A", "B")
            mesh.link("B", "C")
            val listening = UnconfinedTestDispatcher(scope.testScheduler)
            scope.backgroundScope.launch(listening) { repoB.incomingCallOuts.toList(ringsAtB) }
            scope.backgroundScope.launch(listening) { repoC.incomingCallOuts.toList(ringsAtC) }
            scope.backgroundScope.launch(listening) { repoA.answeredCallOuts.toList(confirmedAtA) }
            scope.backgroundScope.launch(listening) { repoB.incomingQuestions.toList(questionsAtB) }
            scope.backgroundScope.launch(listening) { repoC.incomingQuestions.toList(questionsAtC) }
            listOf(repoA, repoB, repoC).forEach { it.startAndAwaitReady() }
            repoA.saveContact(contact("B"))
            repoA.saveContact(contact("C"))
            repoB.saveContact(contact("A"))
            repoB.saveContact(contact("C"))
            repoC.saveContact(trustedA)
            repoC.saveContact(contact("B"))
        }

        fun stop() = listOf(repoA, repoB, repoC).forEach { it.stop() }

        fun callPacket() = mesh.sentLog.first { it.packet.type == PacketType.CALL_OUT }.packet
    }

    @Test
    fun aQuestionCrossesARelayThatCanNotReadIt() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            val id = line.repoA.sendQuestion("C", "Are you hurt?")
            runCurrent()

            assertEquals(listOf(SpokenQuestion("A", "Are you hurt?")), line.questionsAtC)
            assertTrue("Only the addressed phone speaks", line.questionsAtB.isEmpty())
            assertEquals(0, line.securityB.directDecryptCount)
            assertNull("The relay keeps no readable copy", line.daoB.message(id))
            assertEquals(MessageStatus.DELIVERED.name, line.daoA.message(id)?.status)
            assertEquals(1, line.mesh.sentLog.last { it.packet.id == id }.packet.hopCount)
        } finally {
            line.stop()
        }
    }

    @Test
    fun aQuestionAppearsAsAnOrdinaryChatMessage() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            val id = line.repoA.sendQuestion("C", "Are you hurt?")
            runCurrent()
            assertEquals(PacketType.MESSAGE.name, line.daoA.message(id)?.type)
            assertEquals(PacketType.MESSAGE.name, line.daoC.message(id)?.type)
            assertEquals("Are you hurt?", PacketCodec.decodeBody(line.daoC.message(id)!!.body).text)
            assertNull("The sender stops retrying once it arrives", line.daoA.queuedPacket(id))
        } finally {
            line.stop()
        }
    }

    @Test
    fun theSpokenAnswerComesBackAsAnEncryptedChatMessage() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            line.repoA.sendQuestion("C", "Are you hurt?")
            runCurrent()
            val answer = line.repoC.sendDirect("A", "Spoken answer: my leg is stuck", Priority.P0_SOS)
            runCurrent()
            assertEquals("Spoken answer: my leg is stuck", PacketCodec.decodeBody(line.daoA.message(answer)!!.body).text)
            assertEquals(0, line.securityB.directDecryptCount)
        } finally {
            line.stop()
        }
    }

    @Test
    fun aPhoneWithoutAnActiveSosKeepsTheMessageButNeverSpeaksIt() = runTest {
        val line = CallOutLine(this, cAcceptsCalls = false)
        try {
            line.start(this)
            val id = line.repoA.sendQuestion("C", "Are you hurt?")
            runCurrent()
            assertTrue(line.questionsAtC.isEmpty())
            assertNotNull("It still arrives as a message", line.daoC.message(id))
        } finally {
            line.stop()
        }
    }

    @Test
    fun aLateQuestionIsNeverSpoken() = runTest {
        val line = CallOutLine(this, cClock = { System.currentTimeMillis() + 6 * 60 * 1_000 })
        try {
            line.start(this)
            line.repoA.sendQuestion("C", "Are you hurt?")
            runCurrent()
            assertTrue(line.questionsAtC.isEmpty())
        } finally {
            line.stop()
        }
    }

    @Test
    fun aRepeatedQuestionIsSpokenOnce() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            val id = line.repoA.sendQuestion("C", "Are you hurt?")
            runCurrent()
            val relayed = line.mesh.sentLog.last { it.packet.id == id }
            val transportB = line.mesh.phone("B")
            transportB.send(transportB.peers.value.first { it.deviceId == "C" }.endpointId, relayed.packet)
            runCurrent()
            assertEquals(1, line.questionsAtC.size)
        } finally {
            line.stop()
        }
    }

    @Test
    fun onlyAVerifiedContactCanAskAQuestion() = runTest {
        val line = CallOutLine(this, cTrustsA = false)
        try {
            line.start(this)
            line.repoA.sendQuestion("C", "Are you hurt?")
            runCurrent()
            assertTrue(line.questionsAtC.isEmpty())
        } finally {
            line.stop()
        }
    }

    @Test
    fun aCallReachesThePhoneThroughARelayAndIsConfirmed() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()

            assertEquals(listOf("A"), line.ringsAtC)
            assertEquals(listOf("C"), line.confirmedAtA)
            assertEquals(1, line.mesh.sentLog.last { it.packet.type == PacketType.CALL_OUT }.packet.hopCount)
            assertNull("The caller stops retrying once it rings", line.daoA.queuedPacket(line.callPacket().id))
            assertNull("The relay stops retrying once it rings", line.daoB.queuedPacket(line.callPacket().id))
        } finally {
            line.stop()
        }
    }

    @Test
    fun onlyTheCalledPhoneRings() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()
            assertTrue("B is in SOS mode too, but nobody called it", line.ringsAtB.isEmpty())
        } finally {
            line.stop()
        }
    }

    @Test
    fun aPhoneWithoutAnActiveSosStaysSilentAndDoesNotConfirm() = runTest {
        val line = CallOutLine(this, cAcceptsCalls = false)
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()
            assertTrue(line.ringsAtC.isEmpty())
            assertTrue(line.confirmedAtA.isEmpty())
        } finally {
            line.stop()
        }
    }

    @Test
    fun aLateCallNeverRings() = runTest {
        val line = CallOutLine(this, cClock = { System.currentTimeMillis() + 6 * 60 * 1_000 })
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()
            assertTrue(line.ringsAtC.isEmpty())
            assertTrue(line.confirmedAtA.isEmpty())
        } finally {
            line.stop()
        }
    }

    @Test
    fun anUnverifiedCallerCanNotRingAPhone() = runTest {
        val line = CallOutLine(this, cTrustsA = false)
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()
            assertTrue(line.ringsAtC.isEmpty())
        } finally {
            line.stop()
        }
    }

    @Test
    fun aRepeatedPacketRingsOnce() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()
            val relayed = line.mesh.sentLog.last { it.packet.type == PacketType.CALL_OUT }
            val transportB = line.mesh.phone("B")
            val endpoint = transportB.peers.value.first { it.deviceId == "C" }.endpointId
            transportB.send(endpoint, relayed.packet)
            runCurrent()
            assertEquals(listOf("A"), line.ringsAtC)
            assertEquals(listOf("C"), line.confirmedAtA)
        } finally {
            line.stop()
        }
    }

    @Test
    fun aCallExpiresInMinutesAndStaysTopPriority() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()
            assertEquals(120, line.callPacket().retentionSeconds)
            assertEquals(Priority.P0_SOS, line.callPacket().priority)
        } finally {
            line.stop()
        }
    }

    @Test
    fun aCallLeavesNoMessageBehind() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            line.repoA.sendCallOut("C")
            runCurrent()
            assertNull(line.daoA.message(line.callPacket().id))
            assertTrue(line.repoA.sosAlerts.first().isEmpty())
        } finally {
            line.stop()
        }
    }

    @Test
    fun onlyAVerifiedContactCanBeCalled() = runTest {
        val line = CallOutLine(this)
        try {
            line.start(this)
            val unknown = runCatching { line.repoA.sendCallOut("Z") }.exceptionOrNull()
            assertTrue(unknown is IllegalArgumentException)
            line.repoA.saveContact(contact("Z").copy(verified = false))
            val unverified = runCatching { line.repoA.sendCallOut("Z") }.exceptionOrNull()
            assertTrue(unverified is IllegalArgumentException)
        } finally {
            line.stop()
        }
    }

    private fun contact(id: String) = ContactCard(
        deviceId = id,
        displayName = id,
        signingPublicKey = id.toByteArray(),
        encryptionPublicKey = id.toByteArray(),
        verified = true,
    )

    /** Test-only stand-in: the relay test verifies routing, not cryptography. */
    private class TestMessageSecurity(private val card: ContactCard) : MessageSecurity {
        var directDecryptCount = 0
            private set

        override val isDevelopmentPlaintext = true
        override fun localContactCard() = card
        override fun fingerprint(card: ContactCard) = card.deviceId
        override fun encryptDirect(plainText: ByteArray, recipient: ContactCard, associatedData: ByteArray) = plainText
        override fun decryptDirect(cipherText: ByteArray, associatedData: ByteArray): ByteArray {
            directDecryptCount++
            return cipherText
        }
        override fun encryptBroadcast(plainText: ByteArray, associatedData: ByteArray) = plainText
        override fun decryptBroadcast(cipherText: ByteArray, associatedData: ByteArray) = cipherText
        override fun sign(signedBytes: ByteArray) = byteArrayOf(1)
        override fun verify(sender: ContactCard, signedBytes: ByteArray, signature: ByteArray) =
            sender.verified && signature.contentEquals(byteArrayOf(1))
    }

    private class PauseFirstSendTransport(
        private val delegate: MeshTransport,
    ) : MeshTransport by delegate {
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        private var hasPaused = false

        override suspend fun send(endpointId: String, packet: com.slackers.emergencymesh.core.Packet): SendResult {
            if (!hasPaused) {
                hasPaused = true
                firstSendStarted.complete(Unit)
                releaseFirstSend.await()
            }
            return delegate.send(endpointId, packet)
        }
    }

    private class FailAckTransport(
        private val delegate: MeshTransport,
    ) : MeshTransport by delegate {
        val attemptsByPacket = mutableMapOf<String, Int>()

        override suspend fun send(endpointId: String, packet: com.slackers.emergencymesh.core.Packet): SendResult {
            if (packet.type.isAck) {
                attemptsByPacket[packet.id] = (attemptsByPacket[packet.id] ?: 0) + 1
                return SendResult.Failed("Simulated ACK transfer failure")
            }
            return delegate.send(endpointId, packet)
        }
    }

    /** In-memory DAO keeps this unit test independent from Android and Room runtime. */
    internal class InMemoryEmergencyMeshDao : EmergencyMeshDao {
        private val contacts = linkedMapOf<String, ContactEntity>()
        private val messages = linkedMapOf<String, MessageEntity>()
        private val packets = linkedMapOf<String, QueuedPacketEntity>()
        private val seen = linkedMapOf<String, SeenPacketEntity>()
        private val receipts = linkedMapOf<Pair<String, String>, PeerReceiptEntity>()

        override fun observeContacts(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
        override suspend fun contact(deviceId: String) = contacts[deviceId]
        override suspend fun putContact(contact: ContactEntity) { contacts[contact.deviceId] = contact }

        override fun observeConversation(deviceId: String): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.conversationDeviceId == deviceId })

        override fun observeSosMessages(): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.type == "SOS" }.sortedByDescending { it.createdAtMillis })

        override fun observeRecentIncoming(limit: Int): Flow<List<MessageEntity>> =
            flowOf(messages.values.filterNot { it.outgoing }.sortedByDescending { it.createdAtMillis }.take(limit))

        override suspend fun message(messageId: String) = messages[messageId]
        override fun observeMessage(messageId: String): Flow<MessageEntity?> = flowOf(messages[messageId])
        override suspend fun putMessage(message: MessageEntity) { messages[message.messageId] = message }
        override suspend fun updateStatus(messageId: String, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
        }
        override suspend fun recordSentAt(messageId: String, atMillis: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(sentAtMillis = it.sentAtMillis ?: atMillis) }
        }
        override suspend fun recordRelayedAt(messageId: String, atMillis: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(relayedAtMillis = it.relayedAtMillis ?: atMillis) }
        }
        override suspend fun recordDeliveredAt(messageId: String, atMillis: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(deliveredAtMillis = it.deliveredAtMillis ?: atMillis) }
        }
        override suspend fun updateHopCount(messageId: String, hopCount: Int) {
            messages[messageId]?.let { messages[messageId] = it.copy(hopCount = maxOf(it.hopCount, hopCount)) }
        }

        override suspend fun dueOutgoingForPeer(
            peerDeviceId: String,
            nowMillis: Long,
            limit: Int,
        ): List<QueuedPacketEntity> = packets.values.asSequence()
            .filter { it.outgoing && it.expiresAtMillis > nowMillis }
            .filter { it.receivedFromDeviceId != peerDeviceId }
            .filter { packet ->
                val receipt = receipts[packet.packetId to peerDeviceId]
                receipt == null && packet.nextAttemptAtMillis <= nowMillis ||
                    receipt != null && receipt.acknowledgedAtMillis == null && receipt.nextAttemptAtMillis <= nowMillis
            }
            .sortedWith(compareBy<QueuedPacketEntity> { it.priority }.thenBy { it.queuedAtMillis })
            .take(limit)
            .toList()

        override suspend fun queuedPacket(packetId: String) = packets[packetId]
        fun queuedPackets() = packets.values.toList()
        override suspend fun putQueuedPacket(packet: QueuedPacketEntity) { packets[packet.packetId] = packet }
        override suspend fun deleteQueuedPacket(packetId: String) { packets.remove(packetId) }
        override suspend fun pruneExpiredPackets(nowMillis: Long) {
            packets.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
        }
        override suspend fun hasSeen(packetId: String) = packetId in seen
        override suspend fun recordSeen(packet: SeenPacketEntity): Long {
            if (packet.packetId in seen) return -1L
            seen[packet.packetId] = packet
            return seen.size.toLong()
        }
        override suspend fun pruneSeen(nowMillis: Long) {
            seen.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
        }
        override suspend fun receiptsForPacket(packetId: String) =
            receipts.values.filter { it.packetId == packetId }
        override suspend fun receipt(packetId: String, peerDeviceId: String) =
            receipts[packetId to peerDeviceId]
        override suspend fun acknowledgePacketForPeer(packetId: String, peerDeviceId: String, acknowledgedAtMillis: Long) {
            val key = packetId to peerDeviceId
            receipts[key]?.let { receipts[key] = it.copy(acknowledgedAtMillis = acknowledgedAtMillis) }
        }
        override suspend fun putPeerReceipt(receipt: PeerReceiptEntity) {
            receipts[receipt.packetId to receipt.peerDeviceId] = receipt
        }
        override suspend fun pruneOrphanedPeerReceipts() {
            receipts.entries.removeAll { it.value.packetId !in packets }
        }
        override suspend fun saveOutgoing(message: MessageEntity, packet: QueuedPacketEntity) {
            messages[message.messageId] = message
            packets[packet.packetId] = packet
        }
    }
}
