package com.slackers.emergencymesh.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class PeerState { DISCOVERED, CONNECTING, AWAITING_APPROVAL, CONNECTED, DISCONNECTED }

/** [endpointId] is temporary and changes between sessions. [deviceId] is the stable identity. */
data class PeerInfo(
    val endpointId: String,
    val deviceId: String,
    val displayName: String,
    val state: PeerState,
    /** Nearby's verification token. Both users compare it before approving. */
    val authToken: String? = null,
)

data class ContactCard(
    val deviceId: String,
    val displayName: String,
    val signingPublicKey: ByteArray,
    val encryptionPublicKey: ByteArray,
    /** True only after people compared fingerprints. */
    val verified: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is ContactCard &&
            deviceId == other.deviceId &&
            displayName == other.displayName &&
            signingPublicKey.contentEquals(other.signingPublicKey) &&
            encryptionPublicKey.contentEquals(other.encryptionPublicKey) &&
            verified == other.verified

    override fun hashCode(): Int = deviceId.hashCode()
}

/** Declared in order, so status may only move to a higher ordinal. */
enum class MessageStatus { PENDING, SENT, RELAYED, DELIVERED }

data class ChatMessage(
    val messageId: String,
    val conversationDeviceId: String?,
    val senderId: String,
    val outgoing: Boolean,
    val type: PacketType,
    val priority: Priority,
    val body: MessageBody,
    val createdAtMillis: Long,
    val status: MessageStatus,
    val hopCount: Int,
)

sealed interface TransportEvent {
    data class PeerConnected(val peer: PeerInfo) : TransportEvent
    data class PeerDisconnected(val endpointId: String, val deviceId: String) : TransportEvent
    data class PacketReceived(val fromEndpointId: String, val packet: Packet) : TransportEvent
    data class MalformedData(val fromEndpointId: String, val reason: String) : TransportEvent
    data class Failure(val reason: String) : TransportEvent
}

sealed interface SendResult {
    /** Nearby finished the byte transfer. This is not app-level receipt, and is not status SENT. */
    data object TransportComplete : SendResult

    /** A relay node took custody. It never sends receipts, so retrying it would be wasted work. */
    data object Stored : SendResult
    data class Failed(val reason: String) : SendResult
}

enum class DiscoveryMode {
    /** Scan all the time. Use while an SOS is active and for battery comparison runs. */
    CONTINUOUS,

    /** Scan in bursts, and pause scanning once enough peers are connected. */
    BALANCED,
}

/**
 * Moves packets between immediate neighbors only. Relay decisions belong to the engine.
 * Collect [events] before calling [start], because missed events are not replayed.
 */
interface MeshTransport {
    val peers: StateFlow<List<PeerInfo>>
    val events: SharedFlow<TransportEvent>

    fun start(localDeviceId: String, displayName: String)
    fun stop()

    /** The user compared [PeerInfo.authToken] and chose to accept or reject. */
    fun respondToConnection(endpointId: String, accept: Boolean)
    suspend fun send(endpointId: String, packet: Packet): SendResult

    /** Debug only. Null allows everyone. Otherwise only these stable device IDs may connect. */
    fun setDebugAllowlist(deviceIds: Set<String>?)

    fun setDiscoveryMode(mode: DiscoveryMode) {}
}

/** Implemented by M4 with Tink. Failed decryption or verification throws SecurityException. */
interface MessageSecurity {
    /** True only for the labelled development build. It must be false in the final demo. */
    val isDevelopmentPlaintext: Boolean

    /** False until this phone has the group code needed to send and read SOS. */
    val hasGroupKey: Boolean get() = true

    /**
     * Enrolled members type the same code on each phone. Anyone holding it can read SOS.
     * Throws [SecurityException] when the code is too short.
     */
    fun setGroupCode(code: String) {}

    fun localContactCard(): ContactCard
    fun fingerprint(card: ContactCard): String

    fun encryptDirect(plainText: ByteArray, recipient: ContactCard, associatedData: ByteArray): ByteArray
    fun decryptDirect(cipherText: ByteArray, associatedData: ByteArray): ByteArray
    fun encryptBroadcast(plainText: ByteArray, associatedData: ByteArray): ByteArray
    fun decryptBroadcast(cipherText: ByteArray, associatedData: ByteArray): ByteArray

    fun sign(signedBytes: ByteArray): ByteArray
    fun verify(sender: ContactCard, signedBytes: ByteArray, signature: ByteArray): Boolean
}

/** A rescuer's question for this phone to read aloud. */
data class SpokenQuestion(val fromDeviceId: String, val text: String)

/** Implemented by M3. The UI talks to this and never to the transport directly. */
interface MessageRepository {
    val contacts: Flow<List<ContactCard>>
    val sosAlerts: Flow<List<ChatMessage>>

    /** Official alerts this phone showed or sent, newest first. */
    val areaAlerts: Flow<List<ChatMessage>>

    /** The newest received messages, newest first, so a locked phone can alert its owner. */
    val recentIncoming: Flow<List<ChatMessage>>

    /** A verified contact called this phone during its active SOS. Holds the caller's device ID. */
    val incomingCallOuts: SharedFlow<String>

    /** A called phone confirmed that it is ringing. Holds that phone's device ID. */
    val answeredCallOuts: SharedFlow<String>

    /** A verified contact asked this phone a question during its active SOS. */
    val incomingQuestions: SharedFlow<SpokenQuestion>

    fun conversation(deviceId: String): Flow<List<ChatMessage>>
    suspend fun sendDirect(recipientDeviceId: String, text: String, priority: Priority): String
    suspend fun sendSos(body: MessageBody): String
    suspend fun saveContact(card: ContactCard)

    /** Warns everyone in one area. It needs a responder or admin login. */
    suspend fun sendAreaAlert(alert: AreaAlert): String

    /** Asks a phone to ring. It stays silent unless its own SOS is still active. */
    suspend fun sendCallOut(targetDeviceId: String)

    /** Sends a question for a phone to read aloud. It also appears as a normal chat message. */
    suspend fun sendQuestion(targetDeviceId: String, text: String): String
}
