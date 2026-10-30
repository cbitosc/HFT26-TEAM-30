package com.slackers.emergencymesh.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.EncryptedKeyset
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID

/** Uses a throwaway namespace, so it can't touch the phone's real identity. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecurityDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "test-${UUID.randomUUID()}"
    private val prefsName = "${namespace}_keys"
    private val message = "Trapped at the school".toByteArray()
    private val header = "header".toByteArray()

    @After
    fun cleanUp() {
        context.deleteSharedPreferences(prefsName)
    }

    @Test
    fun identitySurvivesRestart() {
        val first = SecurityProvider.open(context, namespace)
        val restarted = SecurityProvider.open(context, namespace)
        val card = first.localContactCard()
        assertArrayEquals(card.signingPublicKey, restarted.localContactCard().signingPublicKey)
        assertArrayEquals(card.encryptionPublicKey, restarted.localContactCard().encryptionPublicKey)
        assertEquals(first.fingerprint(card), restarted.fingerprint(restarted.localContactCard()))
    }

    @Test
    fun messageEncryptedBeforeRestartDecryptsAfterIt() {
        val first = SecurityProvider.open(context, namespace)
        val sent = first.encryptDirect(
            message,
            first.localContactCard().copy(verified = true),
            header,
        )
        val restarted = SecurityProvider.open(context, namespace)
        assertArrayEquals(message, restarted.decryptDirect(sent, header))
    }

    @Test
    fun signatureMadeBeforeRestartVerifiesAfterIt() {
        val first = SecurityProvider.open(context, namespace)
        val signature = first.sign(message)
        val restarted = SecurityProvider.open(context, namespace)
        assertTrue(restarted.verify(restarted.localContactCard(), message, signature))
    }

    @Test
    fun groupKeySurvivesRestart() {
        val first = SecurityProvider.open(context, namespace)
        assertFalse(first.hasGroupKey)
        first.setGroupCode("river-bridge-42")
        val sent = first.encryptBroadcast(message, header)

        val restarted = SecurityProvider.open(context, namespace)
        assertTrue(restarted.hasGroupKey)
        assertArrayEquals(message, restarted.decryptBroadcast(sent, header))
    }

    @Test
    fun twoIdentitiesCannotReadEachOthersMessages() {
        val phoneA = SecurityProvider.open(context, namespace)
        val otherNamespace = "test-${UUID.randomUUID()}"
        try {
            val phoneB = SecurityProvider.open(context, otherNamespace)
            val forB = phoneA.encryptDirect(
                message,
                phoneB.localContactCard().copy(verified = true),
                header,
            )
            assertArrayEquals(message, phoneB.decryptDirect(forB, header))
            assertThrows(SecurityException::class.java) { phoneA.decryptDirect(forB, header) }
        } finally {
            context.deleteSharedPreferences("${otherNamespace}_keys")
        }
    }

    @Test
    fun masterKeyIsInTheAndroidKeystore() {
        SecurityProvider.open(context, namespace)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias("emergencymesh_master_key"))
    }

    @Test
    fun storedKeysetsAreNotReadableAsCleartext() {
        val security = SecurityProvider.open(context, namespace)
        security.setGroupCode("river-bridge-42")
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().commit()

        val stored = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all
        assertTrue(stored.keys.containsAll(listOf("signing", "encryption", "group_key")))
        stored.forEach { (name, value) ->
            val bytes = decodeStored(value as String)
            assertTrue(
                "$name must be an encrypted keyset",
                EncryptedKeyset.parseFrom(bytes).encryptedKeyset.size() > 0,
            )
            assertThrows("$name must not be a cleartext keyset", GeneralSecurityException::class.java) {
                TinkProtoKeysetFormat.parseKeyset(bytes, InsecureSecretKeyAccess.get())
            }
        }
    }

    /** Proves the check above can fail, so it isn't passing just because parsing always fails. */
    @Test
    fun cleartextKeysetWouldBeDetected() {
        TinkMessageSecurity.registerTink()
        val cleartext = TinkProtoKeysetFormat.serializeKeyset(
            KeysetHandle.generateNew(KeyTemplates.get(TinkMessageSecurity.SIGNING_TEMPLATE)),
            InsecureSecretKeyAccess.get(),
        )
        TinkProtoKeysetFormat.parseKeyset(cleartext, InsecureSecretKeyAccess.get())
    }

    @Test
    fun recordsHowLongOpeningKeysTakes() {
        val started = SystemClock.elapsedRealtime()
        SecurityProvider.open(context, namespace)
        val firstOpen = SystemClock.elapsedRealtime() - started
        val reopened = SystemClock.elapsedRealtime()
        SecurityProvider.open(context, namespace)
        val secondOpen = SystemClock.elapsedRealtime() - reopened
        Log.i("EmergencyMesh", "Key open: first ${firstOpen} ms, restart ${secondOpen} ms")
        assertTrue("Opening keys took ${secondOpen} ms", secondOpen < 5_000)
    }

    /** Keysets are hex in preferences, and the group key is Base64. */
    private fun decodeStored(value: String): ByteArray =
        if (value.length % 2 == 0 && value.all { it.isDigit() || it in 'a'..'f' }) {
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            Base64.decode(value, Base64.NO_WRAP)
        }
}
