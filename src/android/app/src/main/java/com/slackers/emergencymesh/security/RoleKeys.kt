package com.slackers.emergencymesh.security

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val KDF_ITERATIONS = 100_000
private const val SEED_BYTES = 32

enum class AppRole(val title: String) { USER("User"), RESPONDER("Responder"), ADMIN("Admin") }

internal fun deriveRoleSeed(role: AppRole, code: String): ByteArray {
    val salt = "EmergencyMesh ${role.name} login v1".toByteArray(Charsets.UTF_8)
    val spec = PBEKeySpec(code.trim().toCharArray(), salt, KDF_ITERATIONS, SEED_BYTES * 8)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal fun rolePublicKey(role: AppRole, code: String): ByteArray =
    Ed25519Sign.KeyPair.newKeyPairFromSeed(deriveRoleSeed(role, code)).publicKey

/**
 * Logins that work with no internet. A role code derives a signing key on the phone, and the app
 * holds only that key's public half, so no secret sits in the app or the repository. The same
 * signature lets every phone check that an alert came from a responder.
 */
class RoleAuthority(
    private val publicKeys: Map<AppRole, ByteArray> = mapOf(
        AppRole.RESPONDER to Base64.getDecoder().decode(RESPONDER_PUBLIC_KEY),
        AppRole.ADMIN to Base64.getDecoder().decode(ADMIN_PUBLIC_KEY),
    ),
) {
    /** Returns a signer only if this code really belongs to this role. */
    fun unlock(role: AppRole, code: String): Ed25519Sign? {
        val pinned = publicKeys[role] ?: return null
        val seed = deriveRoleSeed(role, code)
        val keys = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
        return if (keys.publicKey.contentEquals(pinned)) Ed25519Sign(keys.privateKey) else null
    }

    /** True if a responder or an admin signed this. */
    fun verifies(data: ByteArray, signature: ByteArray): Boolean = publicKeys.values.any { key ->
        try {
            Ed25519Verify(key).verify(signature, data)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private companion object {
        const val RESPONDER_PUBLIC_KEY = "ZvdY1AIRvqNAzel+pcTZo/z+oUk0n1lXEIB4IGAFFWA="
        const val ADMIN_PUBLIC_KEY = "wsPGZeg67OYpQisjHGPBUNWcQurt41igKxCiz7/EtMU="
    }
}

/** Who is using this phone. It starts empty, and a role's key lasts only until the app closes. */
class RoleSession(private val authority: RoleAuthority = RoleAuthority()) {
    private val _role = MutableStateFlow<AppRole?>(null)

    @Volatile
    private var signer: Ed25519Sign? = null

    val role: StateFlow<AppRole?> = _role.asStateFlow()

    fun continueAsUser() {
        signer = null
        _role.value = AppRole.USER
    }

    fun login(role: AppRole, code: String): Boolean {
        val unlocked = authority.unlock(role, code) ?: return false
        signer = unlocked
        _role.value = role
        return true
    }

    fun logOut() {
        signer = null
        _role.value = null
    }

    fun sign(data: ByteArray): ByteArray? = signer?.sign(data)

    fun verifies(data: ByteArray, signature: ByteArray): Boolean = authority.verifies(data, signature)
}
