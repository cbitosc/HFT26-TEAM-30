package com.slackers.emergencymesh.engine

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.ContactExchange
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageSecurity
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.data.ContactEntity
import com.slackers.emergencymesh.data.EmergencyMeshDao
import com.slackers.emergencymesh.data.MessageEntity
import com.slackers.emergencymesh.data.PeerReceiptEntity
import com.slackers.emergencymesh.data.QueuedPacketEntity
import com.slackers.emergencymesh.data.SeenPacketEntity
import com.slackers.emergencymesh.transport.InMemoryMesh
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
            val received = requireNotNull(daoC.message(messageId))
            assertEquals("Relay test", PacketCodec.decodeBody(received.body).text)
            assertEquals(0, securityB.directDecryptCount)
            assertNull("The relay should not create a displayed-message row", daoB.message(messageId))

            val retained = requireNotNull(daoB.queuedPacket(messageId))
            assertEquals(Priority.P2_REGULAR.ordinal, retained.priority)
            assertEquals("A", retained.receivedFromDeviceId)
            assertTrue(
                mesh.sentLog.any {
                    it.fromDeviceId == "B" && it.toDeviceId == "C" &&
                        it.packet.id == messageId && it.packet.hopCount == 1 && it.packet.ttl == 4
                },
            )
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
            repoB.startAndAwaitReady()
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

    /** In-memory DAO keeps this unit test independent from Android and Room runtime. */
    private class InMemoryEmergencyMeshDao : EmergencyMeshDao {
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

        override suspend fun message(messageId: String) = messages[messageId]
        override suspend fun putMessage(message: MessageEntity) { messages[message.messageId] = message }
        override suspend fun updateStatus(messageId: String, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
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
