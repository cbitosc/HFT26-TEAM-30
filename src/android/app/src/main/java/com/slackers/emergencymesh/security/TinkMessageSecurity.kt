package com.slackers.emergencymesh.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.util.SecretBytes
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MessageSecurity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Keeps the derived group key across restarts. */
internal interface GroupKeyStore {
    fun load(): KeysetHandle?
    fun save(key: KeysetHandle)
}

/**
 * Tink-backed security. Direct bodies use hybrid encryption to the recipient's public key, so a
 * relay can't read them. Packets use Ed25519 signatures. SOS uses AES-GCM under a key derived
 * from a group code that members type on each phone, so every code holder can read SOS.
 */
class TinkMessageSecurity internal constructor(
    private val deviceId: String,
    private val displayName: String,
    signingKeys: KeysetHandle,
    encryptionKeys: KeysetHandle,
    private val groupKeys: GroupKeyStore,
) : MessageSecurity {
    private val signer = signingKeys.getPrimitive(PublicKeySign::class.java)
    private val decrypter = encryptionKeys.getPrimitive(HybridDecrypt::class.java)
    private val signingPublicKey =
        TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signingKeys.publicKeysetHandle)
    private val encryptionPublicKey =
        TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encryptionKeys.publicKeysetHandle)

    @Volatile
    private var groupAead: Aead? = groupKeys.load()?.getPrimitive(Aead::class.java)

    override val isDevelopmentPlaintext: Boolean = false

    override val hasGroupKey: Boolean get() = groupAead != null

    override fun setGroupCode(code: String) {
        val key = deriveGroupKey(code)
        groupKeys.save(key)
        groupAead = key.getPrimitive(Aead::class.java)
    }

    override fun localContactCard(): ContactCard =
        ContactCard(deviceId, displayName, signingPublicKey, encryptionPublicKey)

    /** Covers the device ID, so comparing fingerprints pins that ID to these keys. */
    override fun fingerprint(card: ContactCard): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val id = card.deviceId.toByteArray(Charsets.UTF_8)
            out.writeInt(id.size)
            out.write(id)
            out.writeInt(card.signingPublicKey.size)
            out.write(card.signingPublicKey)
            out.writeInt(card.encryptionPublicKey.size)
            out.write(card.encryptionPublicKey)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .take(FINGERPRINT_BYTES)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")
    }

    override fun encryptDirect(
        plainText: ByteArray,
        recipient: ContactCard,
        associatedData: ByteArray,
    ): ByteArray {
        if (!recipient.verified) throw SecurityException("Recipient isn't verified")
        return guarded("Direct encryption failed") {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(recipient.encryptionPublicKey)
                .getPrimitive(HybridEncrypt::class.java)
                .encrypt(plainText, associatedData)
        }
    }

    override fun decryptDirect(cipherText: ByteArray, associatedData: ByteArray): ByteArray =
        guarded("Direct decryption failed") { decrypter.decrypt(cipherText, associatedData) }

    override fun encryptBroadcast(plainText: ByteArray, associatedData: ByteArray): ByteArray =
        guarded("SOS encryption failed") { requireGroupAead().encrypt(plainText, associatedData) }

    override fun decryptBroadcast(cipherText: ByteArray, associatedData: ByteArray): ByteArray =
        guarded("SOS decryption failed") { requireGroupAead().decrypt(cipherText, associatedData) }

    override fun sign(signedBytes: ByteArray): ByteArray =
        guarded("Signing failed") { signer.sign(signedBytes) }

    override fun verify(sender: ContactCard, signedBytes: ByteArray, signature: ByteArray): Boolean =
        try {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(sender.signingPublicKey)
                .getPrimitive(PublicKeyVerify::class.java)
                .verify(signature, signedBytes)
            true
        } catch (_: GeneralSecurityException) {
            false
        }

    private fun requireGroupAead(): Aead =
        groupAead ?: throw SecurityException("Enter the group code to use SOS")

    private inline fun <T> guarded(message: String, block: () -> T): T =
        try {
            block()
        } catch (e: GeneralSecurityException) {
            throw SecurityException(message, e)
        }

    companion object {
        internal const val SIGNING_TEMPLATE = "ED25519"
        internal const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
        private const val FINGERPRINT_BYTES = 16
        private const val MIN_GROUP_CODE_CHARS = 8
        private const val KDF_ITERATIONS = 100_000
        private const val GROUP_KEY_BYTES = 32
        private val GROUP_SALT = "EmergencyMesh group key v1".toByteArray(Charsets.UTF_8)

        init {
            registerTink()
        }

        internal fun registerTink() {
            AeadConfig.register()
            HybridConfig.register()
            SignatureConfig.register()
        }

        /** Every phone with the same code derives the same key, with no secret in the repo. */
        internal fun deriveGroupKey(code: String): KeysetHandle {
            val normalized = code.trim()
            if (normalized.length < MIN_GROUP_CODE_CHARS) {
                throw SecurityException("Use a group code of at least $MIN_GROUP_CODE_CHARS characters")
            }
            val spec = PBEKeySpec(
                normalized.toCharArray(),
                GROUP_SALT,
                KDF_ITERATIONS,
                GROUP_KEY_BYTES * 8,
            )
            val raw = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            val parameters = AesGcmParameters.builder()
                .setKeySizeBytes(GROUP_KEY_BYTES)
                .setIvSizeBytes(12)
                .setTagSizeBytes(16)
                .setVariant(AesGcmParameters.Variant.NO_PREFIX)
                .build()
            val key = AesGcmKey.builder()
                .setParameters(parameters)
                .setKeyBytes(SecretBytes.copyFrom(raw, InsecureSecretKeyAccess.get()))
                .build()
            raw.fill(0)
            return KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
                .build()
        }
    }
}
