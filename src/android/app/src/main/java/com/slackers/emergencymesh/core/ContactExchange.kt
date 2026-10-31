package com.slackers.emergencymesh.core

enum class ContactCardChange { NEW, UNCHANGED, KEYS_CHANGED }

/**
 * Lets directly connected phones swap public contact cards. A card is public, so its body isn't
 * encrypted, and a zero TTL stops it from being relayed. The signature only proves the sender
 * holds the card's signing key. People still have to compare fingerprints to verify a contact.
 */
object ContactExchange {
    private const val RETENTION_SECONDS = 60

    fun buildPacket(security: MessageSecurity, id: String, nowMillis: Long): Packet {
        val card = security.localContactCard()
        val unsigned = Packet(
            type = PacketType.CONTACT_CARD,
            id = id,
            senderId = card.deviceId,
            recipientId = null,
            priority = Priority.P1_EMERGENCY,
            createdAtMillis = nowMillis,
            initialTtl = 0,
            retentionSeconds = RETENTION_SECONDS,
            ttl = 0,
            hopCount = 0,
            encryptedBody = PacketCodec.encodeContactCard(card),
            signature = ByteArray(0),
        )
        return unsigned.copy(signature = security.sign(PacketCodec.signedBytes(unsigned)))
    }

    /**
     * Returns the unverified card, or throws [SecurityException] when it can't be trusted to
     * have come directly from [connectedDeviceId].
     */
    fun readPacket(
        security: MessageSecurity,
        packet: Packet,
        connectedDeviceId: String,
    ): ContactCard {
        if (packet.type != PacketType.CONTACT_CARD) {
            throw SecurityException("Not a contact card packet")
        }
        if (packet.initialTtl != 0 || packet.ttl != 0 || packet.hopCount != 0) {
            throw SecurityException("Contact cards must come directly from the sender")
        }
        val card = try {
            PacketCodec.decodeContactCard(packet.encryptedBody)
        } catch (e: MalformedPacketException) {
            throw SecurityException(e.message)
        }
        if (card.deviceId != packet.senderId || card.deviceId != connectedDeviceId) {
            throw SecurityException("Contact card doesn't match the connected device")
        }
        if (!security.verify(card, PacketCodec.signedBytes(packet), packet.signature)) {
            throw SecurityException("Contact card signature is invalid")
        }
        return card
    }

    /**
     * Save a [ContactCardChange.NEW] card as unverified. Keep the stored card when it is
     * unchanged, and warn the user instead of replacing it when the keys changed.
     */
    fun compare(known: ContactCard?, incoming: ContactCard): ContactCardChange = when {
        known == null -> ContactCardChange.NEW
        known.signingPublicKey.contentEquals(incoming.signingPublicKey) &&
            known.encryptionPublicKey.contentEquals(incoming.encryptionPublicKey) ->
            ContactCardChange.UNCHANGED
        else -> ContactCardChange.KEYS_CHANGED
    }
}
