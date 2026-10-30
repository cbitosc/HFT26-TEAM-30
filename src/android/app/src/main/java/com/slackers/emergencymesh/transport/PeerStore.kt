package com.slackers.emergencymesh.transport

import android.content.Context
import java.util.UUID

/** Peers the user approved, and the debug topology, both kept across restarts. */
internal interface PeerStore {
    fun isApproved(deviceId: String): Boolean
    fun approve(deviceId: String)
    fun debugAllowlist(): Set<String>?
    fun saveDebugAllowlist(deviceIds: Set<String>?)
}

internal class PrefsPeerStore(context: Context) : PeerStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("emergencymesh_transport", Context.MODE_PRIVATE)

    override fun isApproved(deviceId: String): Boolean =
        deviceId in prefs.getStringSet(KEY_APPROVED, emptySet()).orEmpty()

    override fun approve(deviceId: String) {
        val approved = prefs.getStringSet(KEY_APPROVED, emptySet()).orEmpty() + deviceId
        prefs.edit().putStringSet(KEY_APPROVED, approved).apply()
    }

    override fun debugAllowlist(): Set<String>? = prefs.getStringSet(KEY_ALLOWLIST, null)

    override fun saveDebugAllowlist(deviceIds: Set<String>?) {
        prefs.edit().apply {
            if (deviceIds == null) remove(KEY_ALLOWLIST) else putStringSet(KEY_ALLOWLIST, deviceIds)
        }.apply()
    }

    private companion object {
        const val KEY_APPROVED = "approved_device_ids"
        const val KEY_ALLOWLIST = "debug_allowlist"
    }
}

data class LocalDevice(val deviceId: String, val displayName: String)

/** Creates one stable device ID. The Nearby endpoint ID changes, but this one doesn't. */
object LocalDeviceStore {
    private const val PREFS = "emergencymesh_device"
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "display_name"

    fun load(context: Context): LocalDevice {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
        val displayName = prefs.getString(KEY_NAME, null) ?: "Phone ${deviceId.take(4)}"
        return LocalDevice(deviceId, displayName)
    }

    fun saveDisplayName(context: Context, displayName: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, displayName).apply()
    }
}
