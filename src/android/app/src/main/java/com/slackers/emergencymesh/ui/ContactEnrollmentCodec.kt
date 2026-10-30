package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.ContactCard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

/** Public contact transfer text for copy/paste. Serialization is not authentication. */
internal object ContactEnrollmentCodec {
    private const val PREFIX = "EMESH-CONTACT-1:"
    const val MAX_TEXT_LENGTH = 32_768
    private const val MAX_KEY_BYTES = 8_192

    fun encode(card: ContactCard): String {
        validate(card)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.field(card.deviceId.toByteArray(Charsets.UTF_8))
            out.field(card.displayName.toByteArray(Charsets.UTF_8))
            out.field(card.signingPublicKey)
            out.field(card.encryptionPublicKey)
            // verified is deliberately never exported. Trust belongs to this receiver.
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    fun decode(text: String): ContactCard {
        val value = text.trim()
        require(value.length <= MAX_TEXT_LENGTH) { "Contact card is too large." }
        require(value.startsWith(PREFIX)) { "Paste an EMESH-CONTACT-1 public contact card." }
        val bytes = try {
            Base64.getUrlDecoder().decode(value.removePrefix(PREFIX))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Contact card contains invalid transfer text.")
        }
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val card = ContactCard(
                deviceId = String(input.field(512), Charsets.UTF_8),
                displayName = String(input.field(320), Charsets.UTF_8),
                signingPublicKey = input.field(MAX_KEY_BYTES),
                encryptionPublicKey = input.field(MAX_KEY_BYTES),
                verified = false,
            )
            require(input.available() == 0) { "Contact card contains unexpected extra data." }
            validate(card)
            return card
        } catch (_: IOException) {
            throw IllegalArgumentException("Contact card is truncated or has invalid lengths.")
        }
    }

    private fun validate(card: ContactCard) {
        require(card.deviceId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid device ID." }
        require(card.displayName.isNotBlank() && card.displayName.length <= 80 &&
            card.displayName.none { it.isISOControl() }) { "Invalid display name." }
        require(card.signingPublicKey.size in 1..MAX_KEY_BYTES &&
            card.encryptionPublicKey.size in 1..MAX_KEY_BYTES) { "Public keys are missing or too large." }
    }

    private fun DataOutputStream.field(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.field(limit: Int): ByteArray {
        val length = readInt()
        require(length in 1..limit && length <= available()) { "Invalid contact field length." }
        return ByteArray(length).also(::readFully)
    }
}

/** Never silently replace pinned keys or downgrade a previously verified contact. */
internal fun contactForEnrollment(
    incoming: ContactCard,
    existing: ContactCard?,
    localDeviceId: String,
    comparedFingerprint: Boolean,
): ContactCard {
    require(incoming.deviceId != localDeviceId) { "This is your own contact card." }
    if (existing != null) {
        require(existing.deviceId == incoming.deviceId &&
            existing.signingPublicKey.contentEquals(incoming.signingPublicKey) &&
            existing.encryptionPublicKey.contentEquals(incoming.encryptionPublicKey)) {
            "This device ID already has different pinned keys. Ask the security owner to review re-enrollment."
        }
        if (existing.verified) return existing
    }
    return incoming.copy(verified = comparedFingerprint)
}
