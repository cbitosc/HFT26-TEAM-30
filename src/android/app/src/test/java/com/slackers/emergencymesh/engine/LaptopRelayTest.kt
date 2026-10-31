package com.slackers.emergencymesh.engine

import com.slackers.emergencymesh.background.DashboardLink
import com.slackers.emergencymesh.background.LaptopNode
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.MessageSecurity
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.PacketCodec
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.security.GroupKeyStore
import com.slackers.emergencymesh.security.TinkMessageSecurity
import com.slackers.emergencymesh.transport.NearbyMeshTransport
import com.slackers.emergencymesh.transport.NearbyRadio
import com.slackers.emergencymesh.transport.NearbyRadioListener
import com.slackers.emergencymesh.transport.OutgoingPayload
import com.slackers.emergencymesh.transport.PeerStore
import com.slackers.emergencymesh.transport.RadioResult
import com.slackers.emergencymesh.transport.RelayNode
import com.slackers.emergencymesh.transport.TransportConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two phones with no radio link, joined only by a laptop. Set DASHBOARD_CONTRACT_URL and
 * DASHBOARD_CONTRACT_TOKEN to also run this against a real src/dashboard service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaptopRelayTest {
    /** A radio that never finds anyone, so the laptop is the only way across. */
    private class SilentRadio : NearbyRadio {
        override var listener: NearbyRadioListener? = null
        var discoveryStarts = 0

        override fun startAdvertising(name: String, onResult: (RadioResult) -> Unit) = onResult(RadioResult(true))

        override fun startDiscovery(onResult: (RadioResult) -> Unit) {
            discoveryStarts++
            onResult(RadioResult(true))
        }

        override fun stopAdvertising() = Unit
        override fun stopDiscovery() = Unit
        override fun stopAllEndpoints() = Unit
        override fun requestConnection(name: String, endpointId: String, onResult: (RadioResult) -> Unit) = Unit
        override fun acceptConnection(endpointId: String, onResult: (RadioResult) -> Unit) = Unit
        override fun rejectConnection(endpointId: String) = Unit
        override fun disconnect(endpointId: String) = Unit
        override fun preparePayload(bytes: ByteArray) = OutgoingPayload(1, bytes)
        override fun sendPayload(endpointId: String, payload: OutgoingPayload, onResult: (RadioResult) -> Unit) = Unit
    }

    private class NoStore : PeerStore {
        override fun isApproved(deviceId: String) = false
        override fun approve(deviceId: String) = Unit
        override fun debugAllowlist(): Set<String>? = null
        override fun saveDebugAllowlist(deviceIds: Set<String>?) = Unit
    }

    /** Behaves like the laptop service: it keeps everything and never returns a sender's own. */
    private class FakeLaptop : RelayNode {
        val held = mutableListOf<Triple<String, String, ByteArray>>()
        val cursors = HashMap<String, Int>()
        var reachable = true
        var tamper: (ByteArray) -> ByteArray = { it }

        override suspend fun fetch(deviceId: String): List<ByteArray>? {
            if (!reachable) return null
            val from = cursors[deviceId] ?: 0
            cursors[deviceId] = held.size
            return held.drop(from).filter { it.first != deviceId }.map { tamper(it.third) }
        }

        override suspend fun store(
            deviceId: String,
            packetId: String,
            bytes: ByteArray,
            expiresAtMillis: Long,
        ): Boolean {
            if (!reachable) return false
            if (held.none { it.second == packetId }) held += Triple(deviceId, packetId, bytes)
            return true
        }
    }

    private class TestSecurity(private val card: ContactCard) : MessageSecurity {
        override val isDevelopmentPlaintext = true
        override fun localContactCard() = card
        override fun fingerprint(card: ContactCard) = card.deviceId
        override fun encryptDirect(plainText: ByteArray, recipient: ContactCard, associatedData: ByteArray) = plainText
        override fun decryptDirect(cipherText: ByteArray, associatedData: ByteArray) = cipherText
        override fun encryptBroadcast(plainText: ByteArray, associatedData: ByteArray) = plainText
        override fun decryptBroadcast(cipherText: ByteArray, associatedData: ByteArray) = cipherText
        override fun sign(signedBytes: ByteArray) = byteArrayOf(1)
        override fun verify(sender: ContactCard, signedBytes: ByteArray, signature: ByteArray) =
            sender.verified && signature.contentEquals(byteArrayOf(1))
    }

    private class MemoryGroupKeys : GroupKeyStore {
        private var key: KeysetHandle? = null

        override fun load() = key

        override fun save(key: KeysetHandle) {
            this.key = key
        }
    }

    /** The real encryption and signatures the app ships with, not a stand-in. */
    private fun tink(id: String): TinkMessageSecurity {
        TinkMessageSecurity.registerTink()
        return TinkMessageSecurity(
            deviceId = id,
            displayName = id,
            signingKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkMessageSecurity.SIGNING_TEMPLATE)),
            encryptionKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkMessageSecurity.HYBRID_TEMPLATE)),
            groupKeys = MemoryGroupKeys(),
        )
    }

    private fun card(id: String) = ContactCard(id, id, id.toByteArray(), id.toByteArray(), verified = true)

    private fun ByteArray.contains(text: String): Boolean {
        val wanted = text.toByteArray()
        return (0..size - wanted.size).any { start -> wanted.indices.all { this[start + it] == wanted[it] } }
    }

    private inner class Phone(
        val id: String,
        dispatcher: CoroutineDispatcher,
        scope: CoroutineScope,
        node: RelayNode,
        config: TransportConfig,
        clock: () -> Long = System::currentTimeMillis,
        security: MessageSecurity = TestSecurity(card(id)),
    ) {
        val dao = RelayEngineInMemoryMeshTest.InMemoryEmergencyMeshDao()
        val radio = SilentRadio()
        val transport = NearbyMeshTransport(
            radio = radio,
            store = NoStore(),
            dispatcher = dispatcher,
            config = config,
            relayNode = node,
        )
        val repository = MessageRepositoryImpl(dao, transport, security, scope, nowMillis = clock)

        suspend fun start(other: String) = start(card(other))

        suspend fun start(other: ContactCard) {
            repository.saveContact(other)
            repository.startAndAwaitReady()
            transport.start(id, id)
        }

        fun stop() {
            transport.stop()
            repository.stop()
        }
    }

    private val laptop = FakeLaptop()
    private val config = TransportConfig(relayPollMillis = 1_000, relayRetryMillis = 5_000)

    /** Stops both phones even if a check fails, because a running radio never goes idle. */
    private fun twoPhones(test: suspend TestScope.(Phone, Phone) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = { 1_000_000L + testScheduler.currentTime }
        val a = Phone("phone-a", dispatcher, backgroundScope, laptop, config, clock)
        val c = Phone("phone-c", dispatcher, backgroundScope, laptop, config, clock)
        try {
            test(a, c)
        } finally {
            a.stop()
            c.stop()
        }
    }

    @Test
    fun aMessageCrossesTheLaptopAndTheSenderLearnsItArrived() = twoPhones { a, c ->
        a.start("phone-c")
        c.start("phone-a")
        advanceTimeBy(1_500)
        runCurrent()

        val id = a.repository.sendDirect("phone-c", "Crossed by Wi-Fi", Priority.P2_REGULAR)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("Crossed by Wi-Fi", PacketCodec.decodeBody(c.dao.message(id)!!.body).text)
        assertEquals(MessageStatus.DELIVERED.name, a.dao.message(id)?.status)
    }

    @Test
    fun theLaptopOnlyEverHoldsSealedPackets() = twoPhones { a, c ->
        a.start("phone-c")
        c.start("phone-a")
        advanceTimeBy(1_500)
        runCurrent()
        val id = a.repository.sendDirect("phone-c", "Private", Priority.P2_REGULAR)
        runCurrent()

        val held = PacketCodec.decode(laptop.held.single { it.second == id }.third)
        assertEquals(id, held.id)
        assertEquals("Signed by the sender, and carried unchanged", 0, held.hopCount)
    }

    @Test
    fun aPhoneStopsRetryingOnceTheLaptopHasThePacket() = twoPhones { a, c ->
        a.start("phone-c")
        advanceTimeBy(1_500)
        runCurrent()
        a.repository.sendDirect("phone-c", "Nobody is listening yet", Priority.P2_REGULAR)
        runCurrent()
        assertEquals(1, laptop.held.size)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("No repeat uploads", 1, laptop.held.size)
    }

    @Test
    fun aLaterPhoneStillGetsWhatTheLaptopHeld() = twoPhones { a, c ->
        a.start("phone-c")
        advanceTimeBy(1_500)
        runCurrent()
        val id = a.repository.sendDirect("phone-c", "Waiting at base camp", Priority.P2_REGULAR)
        runCurrent()

        c.start("phone-a")
        advanceTimeBy(2_000)
        runCurrent()
        assertNotNull("The sender was gone, but the laptop carried it", c.dao.message(id))
    }

    @Test
    fun theLaptopShowsUpAsAConnectedNeighbor() = twoPhones { a, _ ->
        a.start("phone-c")
        advanceTimeBy(1_500)
        runCurrent()
        val peer = a.transport.peers.value.single()
        assertEquals("Laptop node", peer.displayName)
        assertEquals(PeerState.CONNECTED, peer.state)
    }

    @Test
    fun losingTheLaptopDropsTheLinkAndItReturnsByItself() = twoPhones { a, _ ->
        a.start("phone-c")
        advanceTimeBy(1_500)
        runCurrent()

        laptop.reachable = false
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(a.transport.peers.value.isEmpty())

        laptop.reachable = true
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, a.transport.peers.value.size)
    }

    @Test
    fun aMessageWaitsOnThePhoneWhileTheLaptopIsDown() = twoPhones { a, c ->
        a.start("phone-c")
        c.start("phone-a")
        advanceTimeBy(1_500)
        runCurrent()

        laptop.reachable = false
        val id = a.repository.sendDirect("phone-c", "Held until it returns", Priority.P2_REGULAR)
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(c.dao.message(id))

        laptop.reachable = true
        advanceTimeBy(70_000)
        runCurrent()
        assertNotNull(c.dao.message(id))
    }

    private fun encryptedPhones(test: suspend TestScope.(Phone, Phone) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = { 1_000_000L + testScheduler.currentTime }
        val keysA = tink("phone-a")
        val keysC = tink("phone-c")
        keysA.setGroupCode("practice group code")
        keysC.setGroupCode("practice group code")
        val a = Phone("phone-a", dispatcher, backgroundScope, laptop, config, clock, keysA)
        val c = Phone("phone-c", dispatcher, backgroundScope, laptop, config, clock, keysC)
        try {
            a.start(keysC.localContactCard().copy(verified = true))
            c.start(keysA.localContactCard().copy(verified = true))
            advanceTimeBy(1_500)
            runCurrent()
            test(a, c)
        } finally {
            a.stop()
            c.stop()
        }
    }

    @Test
    fun theLaptopCanNotReadADirectMessage() = encryptedPhones { a, c ->
        val id = a.repository.sendDirect("phone-c", "Trapped at Gate 3", Priority.P0_SOS)
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals("Trapped at Gate 3", PacketCodec.decodeBody(c.dao.message(id)!!.body).text)
        assertTrue(laptop.held.isNotEmpty())
        assertTrue(laptop.held.none { it.third.contains("Trapped") })
    }

    @Test
    fun theLaptopCanNotReadAnSos() = encryptedPhones { a, c ->
        val id = a.repository.sendSos(MessageBody("Water rising at Gate 3"))
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals("Water rising at Gate 3", PacketCodec.decodeBody(c.dao.message(id)!!.body).text)
        assertTrue(laptop.held.none { it.third.contains("Water rising") })
    }

    @Test
    fun aPacketChangedInTheCiphertextIsRejected() = encryptedPhones { a, c ->
        laptop.tamper = { packet -> packet.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } }
        val id = a.repository.sendDirect("phone-c", "Genuine", Priority.P2_REGULAR)
        advanceTimeBy(3_000)
        runCurrent()
        assertNull(c.dao.message(id))
    }

    @Test
    fun aPacketWithAChangedSignatureIsRejected() = encryptedPhones { a, c ->
        laptop.tamper = { packet -> packet.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } }
        val id = a.repository.sendDirect("phone-c", "Genuine", Priority.P2_REGULAR)
        advanceTimeBy(3_000)
        runCurrent()
        assertNull(c.dao.message(id))
    }

    @Test
    fun aPacketForgedByTheLaptopIsRejected() = encryptedPhones { a, c ->
        val before = a.repository.sendDirect("phone-c", "Genuine", Priority.P2_REGULAR)
        advanceTimeBy(3_000)
        runCurrent()
        val genuine = PacketCodec.decode(laptop.held.first { it.second == before }.third)
        val forgedId = "forged-by-the-laptop"
        val forged = genuine.copy(id = forgedId, createdAtMillis = genuine.createdAtMillis + 1)
        laptop.held += Triple("laptop-node", forgedId, PacketCodec.encode(forged))
        advanceTimeBy(3_000)
        runCurrent()
        assertNull("A signature can't be moved to a different packet", c.dao.message(forgedId))
    }

    @Test
    fun aPhoneKeepsLookingForRealNeighborsWhileOnTheLaptop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oneLink = config.copy(targetLinks = 1, burstMillis = 1_000, idleMillis = 1_000)
        val phone = Phone("phone-a", dispatcher, backgroundScope, laptop, oneLink)
        try {
            phone.start("phone-c")
            advanceTimeBy(2_500)
            runCurrent()
            assertEquals("Connected to the laptop", 1, phone.transport.peers.value.size)
            val before = phone.radio.discoveryStarts
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("A laptop link must not fill the radio link target", phone.radio.discoveryStarts > before)
        } finally {
            phone.stop()
        }
    }

    @Test
    fun twoPhonesTalkThroughTheRealLaptopService() {
        val url = System.getenv("DASHBOARD_CONTRACT_URL")
        assumeTrue(url != null)
        val token = System.getenv("DASHBOARD_CONTRACT_TOKEN").orEmpty()
        val stamp = System.currentTimeMillis()
        val thread = newSingleThreadContext("phones")
        val scope = CoroutineScope(SupervisorJob() + thread)
        val link = { MutableStateFlow<DashboardLink?>(DashboardLink(url, token, 0, useAsNode = true)) }
        val fast = TransportConfig(relayPollMillis = 100, relayRetryMillis = 500)
        val keysA = tink("phone-a-$stamp")
        val keysC = tink("phone-c-$stamp")
        val a = Phone(keysA.localContactCard().deviceId, thread, scope, LaptopNode(link()), fast, security = keysA)
        val c = Phone(keysC.localContactCard().deviceId, thread, scope, LaptopNode(link()), fast, security = keysC)
        try {
            runBlocking {
                withContext(thread) {
                    a.start(keysC.localContactCard().copy(verified = true))
                    c.start(keysA.localContactCard().copy(verified = true))
                }
                val id = withContext(thread) { a.repository.sendDirect(c.id, "Real laptop", Priority.P0_SOS) }
                withTimeout(10_000) {
                    while (withContext(thread) { a.dao.message(id)?.status } != MessageStatus.DELIVERED.name) {
                        delay(50)
                    }
                }
                val received = withContext(thread) { c.dao.message(id) }
                assertEquals("Real laptop", PacketCodec.decodeBody(received!!.body).text)

                // Ask the real service for everything it holds, as a phone that isn't involved.
                val held = LaptopNode(link()).fetch("observer-$stamp").orEmpty()
                assertTrue(held.isNotEmpty())
                assertTrue(held.none { it.contains("Real laptop") })
            }
        } finally {
            runBlocking {
                withContext(thread) {
                    a.stop()
                    c.stop()
                }
            }
            scope.cancel()
            thread.close()
        }
    }
}
