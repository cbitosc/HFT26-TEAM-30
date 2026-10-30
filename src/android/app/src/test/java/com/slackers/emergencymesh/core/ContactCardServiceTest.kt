package com.slackers.emergencymesh.core

import com.slackers.emergencymesh.transport.InMemoryMesh
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CardSecurity(deviceId: String, key: Byte = deviceId.first().code.toByte()) : MessageSecurity {
    private val card = ContactCard(deviceId, "Phone $deviceId", byteArrayOf(key), byteArrayOf(key, key))

    override val isDevelopmentPlaintext = true

    override fun localContactCard() = card

    override fun fingerprint(card: ContactCard) = card.deviceId

    override fun encryptDirect(plainText: ByteArray, recipient: ContactCard, associatedData: ByteArray) = plainText

    override fun decryptDirect(cipherText: ByteArray, associatedData: ByteArray) = cipherText

    override fun encryptBroadcast(plainText: ByteArray, associatedData: ByteArray) = plainText

    override fun decryptBroadcast(cipherText: ByteArray, associatedData: ByteArray) = cipherText

    override fun sign(signedBytes: ByteArray) = card.signingPublicKey + signedBytes.size.toByte()

    override fun verify(sender: ContactCard, signedBytes: ByteArray, signature: ByteArray) =
        signature.contentEquals(sender.signingPublicKey + signedBytes.size.toByte())
}

private class MemoryBook(vararg cards: ContactCard) : ContactBook {
    val cards = cards.associateBy { it.deviceId }.toMutableMap()

    override suspend fun find(deviceId: String) = cards[deviceId]

    override suspend fun save(card: ContactCard) {
        cards[card.deviceId] = card
    }
}

/** Fails its first sends, like a link that has only just connected. */
private class FlakyTransport(private val failures: Int) : MeshTransport {
    override val peers = MutableStateFlow(emptyList<PeerInfo>())
    override val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
    var attempts = 0

    override fun start(localDeviceId: String, displayName: String) = Unit

    override fun stop() = Unit

    override fun respondToConnection(endpointId: String, accept: Boolean) = Unit

    override fun setDebugAllowlist(deviceIds: Set<String>?) = Unit

    override suspend fun send(endpointId: String, packet: Packet): SendResult {
        attempts++
        return if (attempts <= failures) SendResult.Failed("Link busy") else SendResult.TransportComplete
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContactCardServiceTest {
    private class Phone(val transport: InMemoryMesh.SimulatedPhone, val book: MemoryBook, val service: ContactCardService)

    private fun TestScope.phone(mesh: InMemoryMesh, id: String, book: MemoryBook = MemoryBook()): Phone {
        val transport = mesh.phone(id)
        val service = ContactCardService(transport, CardSecurity(id), book)
        service.start(backgroundScope)
        return Phone(transport, book, service)
    }

    @Test
    fun connectedPhonesSwapCards() = runTest {
        val mesh = InMemoryMesh()
        val a = phone(mesh, "A")
        val b = phone(mesh, "B")
        mesh.link("A", "B")
        runCurrent()
        assertEquals("Phone B", a.book.cards["B"]?.displayName)
        assertEquals("Phone A", b.book.cards["A"]?.displayName)
    }

    @Test
    fun receivedCardsStayUnverified() = runTest {
        val mesh = InMemoryMesh()
        val a = phone(mesh, "A")
        phone(mesh, "B")
        mesh.link("A", "B")
        runCurrent()
        assertFalse(a.book.cards.getValue("B").verified)
    }

    @Test
    fun cardsAreNotPassedAlongTheChain() = runTest {
        val mesh = InMemoryMesh()
        val a = phone(mesh, "A")
        val b = phone(mesh, "B")
        val c = phone(mesh, "C")
        mesh.link("A", "B")
        mesh.link("B", "C")
        runCurrent()
        assertEquals(setOf("A", "C"), b.book.cards.keys)
        assertEquals(setOf("B"), a.book.cards.keys)
        assertEquals(setOf("B"), c.book.cards.keys)
    }

    @Test
    fun verifiedContactStaysVerified() = runTest {
        val mesh = InMemoryMesh()
        val verifiedB = CardSecurity("B").localContactCard().copy(verified = true)
        val a = phone(mesh, "A", MemoryBook(verifiedB))
        phone(mesh, "B")
        mesh.link("A", "B")
        runCurrent()
        assertTrue(a.book.cards.getValue("B").verified)
        assertTrue(a.service.keyWarnings.value.isEmpty())
    }

    @Test
    fun changedKeysKeepTheStoredCardAndWarn() = runTest {
        val mesh = InMemoryMesh()
        val pinned = CardSecurity("B", key = 9).localContactCard().copy(verified = true)
        val a = phone(mesh, "A", MemoryBook(pinned))
        phone(mesh, "B")
        mesh.link("A", "B")
        runCurrent()
        val stored = a.book.cards.getValue("B")
        assertArrayEquals(pinned.signingPublicKey, stored.signingPublicKey)
        assertTrue(stored.verified)
        assertEquals(listOf(ContactKeyWarning("B", "Phone B")), a.service.keyWarnings.value)
    }

    @Test
    fun repeatedKeyChangeWarnsOnce() = runTest {
        val mesh = InMemoryMesh()
        val pinned = CardSecurity("B", key = 9).localContactCard()
        val a = phone(mesh, "A", MemoryBook(pinned))
        val b = mesh.phone("B")
        mesh.link("A", "B")
        repeat(2) {
            b.send(a.transport.endpointId, ContactExchange.buildPacket(CardSecurity("B"), "packet-$it", 1L))
        }
        runCurrent()
        assertEquals(1, a.service.keyWarnings.value.size)
    }

    @Test
    fun cardClaimingAnotherDeviceIsDropped() = runTest {
        val mesh = InMemoryMesh()
        val a = phone(mesh, "A")
        val attacker = mesh.phone("B")
        mesh.link("A", "B")
        runCurrent()
        val forged = ContactExchange.buildPacket(CardSecurity("C"), "packet-1", 1L)
        attacker.send(a.transport.endpointId, forged)
        runCurrent()
        assertNull(a.book.cards["C"])
    }

    @Test
    fun cardWithABadSignatureIsDropped() = runTest {
        val mesh = InMemoryMesh()
        val a = phone(mesh, "A")
        val b = mesh.phone("B")
        mesh.link("A", "B")
        runCurrent()
        val tampered = ContactExchange.buildPacket(CardSecurity("B"), "packet-1", 1L)
            .copy(signature = byteArrayOf(1))
        b.send(a.transport.endpointId, tampered)
        runCurrent()
        assertNull(a.book.cards["B"])
    }

    @Test
    fun otherPacketTypesAreIgnored() = runTest {
        val mesh = InMemoryMesh()
        val a = phone(mesh, "A")
        val b = mesh.phone("B")
        mesh.link("A", "B")
        runCurrent()
        val message = ContactExchange.buildPacket(CardSecurity("B"), "packet-1", 1L)
            .copy(type = PacketType.MESSAGE)
        b.send(a.transport.endpointId, message)
        runCurrent()
        assertTrue(a.book.cards.isEmpty())
    }

    @Test
    fun newCardGetsAReplySoAnExchangeCantStayOneSided() = runTest {
        val mesh = InMemoryMesh()
        val a = phone(mesh, "A")
        val b = mesh.phone("B")
        mesh.link("A", "B")
        runCurrent()
        val sentBefore = mesh.sentLog.count { it.fromDeviceId == "A" }
        b.send(a.transport.endpointId, ContactExchange.buildPacket(CardSecurity("B"), "packet-1", 1L))
        runCurrent()
        assertEquals(sentBefore + 1, mesh.sentLog.count { it.fromDeviceId == "A" })
    }

    @Test
    fun failedSendIsRetried() = runTest {
        val transport = FlakyTransport(failures = 2)
        ContactCardService(transport, CardSecurity("A"), MemoryBook()).start(backgroundScope)
        transport.events.emit(TransportEvent.PeerConnected(peer("B")))
        runCurrent()
        assertEquals(1, transport.attempts)
        advanceTimeBy(4_100)
        runCurrent()
        assertEquals(3, transport.attempts)
    }

    @Test
    fun sendStopsRetryingAfterThreeAttempts() = runTest {
        val transport = FlakyTransport(failures = 10)
        ContactCardService(transport, CardSecurity("A"), MemoryBook()).start(backgroundScope)
        transport.events.emit(TransportEvent.PeerConnected(peer("B")))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, transport.attempts)
    }

    @Test
    fun subscribesBeforeStartReturns() = runTest {
        val transport = FlakyTransport(failures = 0)
        ContactCardService(transport, CardSecurity("A"), MemoryBook()).start(backgroundScope)
        assertTrue(transport.events.tryEmit(TransportEvent.PeerConnected(peer("B"))))
        runCurrent()
        assertEquals(1, transport.attempts)
    }

    private fun peer(id: String) = PeerInfo("endpoint-$id", id, "Phone $id", PeerState.CONNECTED)
}
