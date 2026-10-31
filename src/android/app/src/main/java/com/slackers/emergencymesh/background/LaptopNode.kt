package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.transport.RelayNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

internal data class NodePackets(val cursor: Long, val packets: List<ByteArray>)

/** The reply is a cursor, then one Base64 packet per line. Null means it can't be trusted. */
internal fun parseNodePackets(reply: String): NodePackets? {
    val lines = reply.lines().filter { it.isNotEmpty() }
    val cursor = lines.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val packets = lines.drop(1).map { line ->
        try {
            Base64.getDecoder().decode(line)
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    return NodePackets(cursor, packets)
}

/** Talks to the laptop service in src/dashboard. It's off unless the dashboard link asks for it. */
internal class LaptopNode(private val link: StateFlow<DashboardLink?>) : RelayNode {
    private var cursor = 0L
    private var cursorFor: String? = null

    override suspend fun fetch(deviceId: String): List<ByteArray>? {
        val target = link.value?.takeIf { it.useAsNode } ?: return null
        if (cursorFor != target.url) {
            cursorFor = target.url
            cursor = 0
        }
        val device = URLEncoder.encode(deviceId, "UTF-8")
        val reply = withContext(Dispatchers.IO) {
            request(target, "/mesh/packets?device=$device&after=$cursor", body = null)
        } ?: return null
        val parsed = parseNodePackets(reply) ?: return null
        cursor = parsed.cursor
        return parsed.packets
    }

    override suspend fun store(
        deviceId: String,
        packetId: String,
        bytes: ByteArray,
        expiresAtMillis: Long,
    ): Boolean {
        val target = link.value?.takeIf { it.useAsNode } ?: return false
        val body = nodePacketJson(deviceId, packetId, bytes, expiresAtMillis)
        return withContext(Dispatchers.IO) { request(target, "/mesh/packets", body) } != null
    }

    private fun request(target: DashboardLink, path: String, body: String?): String? =
        try {
            val connection = URL(target.url + path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("X-Upload-Token", target.token)
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { it.readBytes().decodeToString() }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
}

/** IDs are UUIDs and the data is Base64, so none of these values need JSON escaping. */
internal fun nodePacketJson(
    deviceId: String,
    packetId: String,
    bytes: ByteArray,
    expiresAtMillis: Long,
): String {
    val data = Base64.getEncoder().encodeToString(bytes)
    return "{\"device\":\"$deviceId\",\"id\":\"$packetId\"," +
        "\"expires_at\":${expiresAtMillis / 1000.0},\"data\":\"$data\"}"
}
