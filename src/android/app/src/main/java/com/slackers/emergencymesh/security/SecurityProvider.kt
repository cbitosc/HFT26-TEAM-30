package com.slackers.emergencymesh.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.slackers.emergencymesh.core.MessageSecurity
import com.slackers.emergencymesh.transport.LocalDeviceStore

/** Opens this phone's identity, creating it on first use. Private keys never leave the phone. */
object SecurityProvider {
    private const val DEFAULT_NAMESPACE = "emergencymesh"

    fun create(context: Context): MessageSecurity = open(context, DEFAULT_NAMESPACE)

    /** Keeps separate key files so device tests can't touch a phone's real identity. */
    internal fun open(context: Context, namespace: String): TinkMessageSecurity {
        TinkMessageSecurity.registerTink()
        val device = LocalDeviceStore.load(context)
        val keys = KeystoreKeys(context.applicationContext, namespace)
        return TinkMessageSecurity(
            deviceId = device.deviceId,
            displayName = device.displayName,
            signingKeys = keys.keyset("signing", TinkMessageSecurity.SIGNING_TEMPLATE),
            encryptionKeys = keys.keyset("encryption", TinkMessageSecurity.HYBRID_TEMPLATE),
            groupKeys = keys.groupKeyStore(),
        )
    }
}

/** Stores keysets encrypted under a master key held by the Android Keystore. */
internal class KeystoreKeys(private val context: Context, private val namespace: String) {
    private val prefsName = "${namespace}_keys"

    fun keyset(name: String, template: String): KeysetHandle =
        manager(name, template).keysetHandle

    fun groupKeyStore(): GroupKeyStore {
        val wrapper = keyset("group_wrapper", "AES256_GCM").getPrimitive(Aead::class.java)
        return AndroidGroupKeyStore(context, prefsName, wrapper)
    }

    private fun manager(name: String, template: String): AndroidKeysetManager {
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(context, name, prefsName)
            .withKeyTemplate(KeyTemplates.get(template))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        // Never run with private keys stored in the clear.
        if (!manager.isUsingKeystore) throw SecurityException("Android Keystore is unavailable")
        return manager
    }

    private companion object {
        const val MASTER_KEY_URI = "android-keystore://emergencymesh_master_key"
    }
}

private class AndroidGroupKeyStore(
    context: Context,
    prefsName: String,
    private val wrapper: Aead,
) : GroupKeyStore {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun load(): KeysetHandle? {
        val stored = prefs.getString(KEY_GROUP, null) ?: return null
        val encrypted = Base64.decode(stored, Base64.NO_WRAP)
        return TinkProtoKeysetFormat.parseEncryptedKeyset(encrypted, wrapper, CONTEXT)
    }

    override fun save(key: KeysetHandle) {
        val encrypted = TinkProtoKeysetFormat.serializeEncryptedKeyset(key, wrapper, CONTEXT)
        prefs.edit().putString(KEY_GROUP, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private companion object {
        const val KEY_GROUP = "group_key"
        val CONTEXT = "emergencymesh group key".toByteArray(Charsets.UTF_8)
    }
}
