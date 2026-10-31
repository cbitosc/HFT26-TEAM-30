package com.slackers.emergencymesh.transport

/**
 * A neighbor reached over Wi-Fi instead of radio, such as a laptop at base camp. It holds no keys,
 * so it can't read what it carries, and phones still check every signature themselves.
 */
interface RelayNode {
    /** Returns what the node holds for this phone, or null if it can't be reached. */
    suspend fun fetch(deviceId: String): List<ByteArray>?

    /** Returns true once the node has taken the packet, so this phone can stop retrying it. */
    suspend fun store(deviceId: String, packetId: String, bytes: ByteArray, expiresAtMillis: Long): Boolean
}
