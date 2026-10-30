package com.slackers.emergencymesh.security

import android.content.Context
import android.util.Base64
import com.slackers.emergencymesh.core.MessageSecurity
import com.slackers.emergencymesh.transport.LocalDeviceStore
import java.security.SecureRandom

/** Debug builds only. Member 4 replaces this with the Tink provider in both build types. */
object SecurityProvider {
    private const val PREFS = "emergencymesh_dev_security"
    private const val KEY_SIZE = 32

    fun create(context: Context): MessageSecurity {
        val device = LocalDeviceStore.load(context)
        return DevelopmentPlaintextSecurity(
            deviceId = device.deviceId,
            displayName = device.displayName,
            signingPublicKey = stableKey(context, "signing"),
            encryptionPublicKey = stableKey(context, "encryption"),
        )
    }

    /** Public test values, stable so a phone's fingerprint survives restarts. */
    private fun stableKey(context: Context, name: String): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(name, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(KEY_SIZE).also(SecureRandom()::nextBytes)
        prefs.edit().putString(name, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        return key
    }
}
