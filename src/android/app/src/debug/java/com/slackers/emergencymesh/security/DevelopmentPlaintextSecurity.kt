package com.slackers.emergencymesh.security

import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MessageSecurity
import java.security.MessageDigest

/**
 * NOT SECURE. A debug-only stand-in so relay can be tested before Member 4's Tink provider lands.
 * Bodies stay readable and the "signature" is a public hash, so anyone can forge it. It lives in
 * the debug source set so release builds can't contain it.
 */
class DevelopmentPlaintextSecurity(
    private val deviceId: String,
    private val displayName: String,
    private val signingPublicKey: ByteArray,
    private val encryptionPublicKey: ByteArray,
) : MessageSecurity {
    override val isDevelopmentPlaintext: Boolean = true

    override fun localContactCard(): ContactCard =
        ContactCard(deviceId, displayName, signingPublicKey, encryptionPublicKey)

    override fun fingerprint(card: ContactCard): String =
        sha256(card.signingPublicKey, card.encryptionPublicKey)
            .take(FINGERPRINT_BYTES)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")

    override fun encryptDirect(
        plainText: ByteArray,
        recipient: ContactCard,
        associatedData: ByteArray,
    ): ByteArray = wrap(plainText, associatedData)

    override fun decryptDirect(cipherText: ByteArray, associatedData: ByteArray): ByteArray =
        unwrap(cipherText, associatedData)

    override fun encryptBroadcast(plainText: ByteArray, associatedData: ByteArray): ByteArray =
        wrap(plainText, associatedData)

    override fun decryptBroadcast(cipherText: ByteArray, associatedData: ByteArray): ByteArray =
        unwrap(cipherText, associatedData)

    override fun sign(signedBytes: ByteArray): ByteArray = sha256(signingPublicKey, signedBytes)

    override fun verify(sender: ContactCard, signedBytes: ByteArray, signature: ByteArray): Boolean =
        MessageDigest.isEqual(sha256(sender.signingPublicKey, signedBytes), signature)

    /** Binds the body to its header, so test mix-ups fail the same way real decryption would. */
    private fun wrap(plainText: ByteArray, associatedData: ByteArray): ByteArray =
        MARKER + sha256(associatedData) + plainText

    private fun unwrap(cipherText: ByteArray, associatedData: ByteArray): ByteArray {
        val headerSize = MARKER.size + HASH_BYTES
        if (cipherText.size < headerSize ||
            !cipherText.copyOfRange(0, MARKER.size).contentEquals(MARKER)
        ) {
            throw SecurityException("Not a development plaintext body")
        }
        val binding = cipherText.copyOfRange(MARKER.size, headerSize)
        if (!MessageDigest.isEqual(binding, sha256(associatedData))) {
            throw SecurityException("Body doesn't match its packet header")
        }
        return cipherText.copyOfRange(headerSize, cipherText.size)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private companion object {
        const val FINGERPRINT_BYTES = 12
        const val HASH_BYTES = 32
        val MARKER = "DEV-PLAINTEXT".toByteArray(Charsets.US_ASCII)
    }
}
