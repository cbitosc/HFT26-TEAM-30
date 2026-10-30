package com.slackers.emergencymesh.transport

import kotlin.math.min

internal data class EndpointIdentity(val deviceId: String, val displayName: String)

/** Nearby endpoint names carry the stable device identity between phones. */
internal object EndpointName {
    private const val PREFIX = "em1"
    private const val SEPARATOR = '|'
    private const val MAX_BYTES = 131

    fun encode(deviceId: String, displayName: String): String {
        require(SEPARATOR !in deviceId) { "Device ID can't contain $SEPARATOR" }
        val header = "$PREFIX$SEPARATOR$deviceId$SEPARATOR"
        val name = StringBuilder()
        var size = header.toByteArray().size
        for (char in displayName.replace(SEPARATOR, ' ')) {
            size += char.toString().toByteArray().size
            if (size > MAX_BYTES) break
            name.append(char)
        }
        return header + name
    }

    fun decode(name: String): EndpointIdentity? {
        val parts = name.split(SEPARATOR, limit = 3)
        if (parts.size != 3 || parts[0] != PREFIX || parts[1].isEmpty()) return null
        return EndpointIdentity(parts[1], parts[2])
    }
}

internal data class TransportConfig(
    val initiatorFallbackMillis: Long = 8_000,
    val baseBackoffMillis: Long = 2_000,
    val maxBackoffMillis: Long = 30_000,
    val maxConnectAttempts: Int = 8,
    val connectTimeoutMillis: Long = 30_000,
    val sendTimeoutMillis: Long = 15_000,
    val burstMillis: Long = 15_000,
    val idleMillis: Long = 45_000,
    val targetLinks: Int = 3,
)

internal class ConnectionPolicy(private val config: TransportConfig) {
    /**
     * Both phones discover each other, so simultaneous requests can collide. The lower ID asks
     * first. The higher ID asks later only in case the other phone never saw it.
     */
    fun initiatorDelayMillis(localDeviceId: String, remoteDeviceId: String): Long =
        if (localDeviceId < remoteDeviceId) 0 else config.initiatorFallbackMillis

    fun backoffMillis(failedAttempts: Int): Long {
        if (failedAttempts <= 0) return 0
        val shift = min(failedAttempts - 1, 10)
        return min(config.maxBackoffMillis, config.baseBackoffMillis shl shift)
    }

    fun isAllowed(allowlist: Set<String>?, deviceId: String): Boolean =
        allowlist == null || deviceId in allowlist
}
