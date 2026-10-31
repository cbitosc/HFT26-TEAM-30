package com.slackers.emergencymesh.background

import android.content.Context
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MessageRepository
import com.slackers.emergencymesh.core.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

private const val MAX_ALERT_ID_CHARS = 64
private const val MAX_TEXT_CHARS = 2000
private const val MAX_SENDER_CHARS = 64
private const val STATUS_OFF = "Off. No alerts leave this phone."

/** Where this phone uploads SOS alerts. Nothing leaves the phone until someone sets this up. */
data class DashboardLink(
    val url: String,
    val token: String,
    val sinceMillis: Long,
    val useAsNode: Boolean = false,
)

internal sealed interface UploadOutcome {
    data object Accepted : UploadOutcome

    /** The dashboard will never take this alert, so trying again can't help. */
    data object Refused : UploadOutcome
    data class Unreachable(val reason: String) : UploadOutcome
}

/** Returns a URL to store, or null. Only the scheme and host are required. */
internal fun normalizeDashboardUrl(raw: String): String? {
    val text = raw.trim().trimEnd('/')
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme != "http" && uri.scheme != "https") return null
    return text.takeIf { uri.host != null }
}

private fun jsonText(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character < ' ' -> append("\\u%04x".format(character.code))
            else -> append(character)
        }
    }
    append('"')
}

/** One alert per request, so a single alert the dashboard refuses can't hold back the others. */
internal fun sosUploadJson(alert: ChatMessage, senderName: String?): String {
    val fields = mutableListOf(
        "\"id\":${jsonText(alert.messageId)}",
        "\"text\":${jsonText(alert.body.text.ifBlank { "SOS with no message" }.take(MAX_TEXT_CHARS))}",
        "\"priority\":\"${if (alert.priority == Priority.P0_SOS) "P0" else "P1"}\"",
        "\"sender\":${jsonText((senderName?.takeIf { it.isNotBlank() } ?: "Phone ${alert.senderId.take(4)}").take(MAX_SENDER_CHARS))}",
        "\"hops\":${alert.hopCount.coerceIn(0, 64)}",
        "\"created_at\":${alert.createdAtMillis / 1000.0}",
    )
    val latitude = alert.body.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
    val longitude = alert.body.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
    if (latitude != null && longitude != null) {
        fields += "\"latitude\":$latitude"
        fields += "\"longitude\":$longitude"
    }
    return fields.joinToString(",", prefix = "{\"alerts\":[{", postfix = "}]}")
}

/**
 * Copies SOS alerts to the online dashboard when this phone can reach it. The mesh never waits
 * for this. Direct messages stay on the phone, because they are end-to-end encrypted.
 */
internal class DashboardUploader(
    private val alerts: Flow<List<ChatMessage>>,
    private val contacts: Flow<List<ContactCard>>,
    private val link: StateFlow<DashboardLink?>,
    private val post: suspend (DashboardLink, String) -> UploadOutcome,
    private val retryMillis: Long = 30_000,
) {
    private val handled = HashSet<String>()
    private var handledFor: DashboardLink? = null
    private var uploaded = 0
    private val _status = MutableStateFlow(STATUS_OFF)

    val status: StateFlow<String> = _status.asStateFlow()

    suspend fun run() {
        combine(alerts, contacts, link) { messages, people, target ->
            Triple(messages, people.associate { it.deviceId to it.displayName }, target)
        }.collectLatest { (messages, names, target) ->
            if (target == null) {
                _status.value = STATUS_OFF
            } else {
                upload(messages, names, target)
            }
        }
    }

    private suspend fun upload(messages: List<ChatMessage>, names: Map<String, String>, target: DashboardLink) {
        if (handledFor != target) {
            handledFor = target
            handled.clear()
            uploaded = 0
        }
        while (true) {
            val pending = messages.filter {
                it.createdAtMillis >= target.sinceMillis && it.messageId !in handled &&
                    it.messageId.length <= MAX_ALERT_ID_CHARS
            }
            var reason: String? = null
            for (alert in pending) {
                when (val outcome = post(target, sosUploadJson(alert, names[alert.senderId]))) {
                    UploadOutcome.Accepted -> {
                        handled += alert.messageId
                        uploaded++
                    }
                    UploadOutcome.Refused -> handled += alert.messageId
                    is UploadOutcome.Unreachable -> {
                        reason = outcome.reason
                        break
                    }
                }
            }
            if (reason == null) {
                _status.value = when (uploaded) {
                    0 -> "On. Waiting for an SOS alert."
                    1 -> "On. 1 alert uploaded."
                    else -> "On. $uploaded alerts uploaded."
                }
                return
            }
            _status.value = "$reason. Trying again soon."
            delay(retryMillis)
        }
    }
}

/** What the screens use to set up the upload and see how it's going. */
class DashboardConnection internal constructor(
    private val store: DashboardLinkStore,
    private val uploader: DashboardUploader,
) {
    val link: StateFlow<DashboardLink?> get() = store.link
    val status: StateFlow<String> get() = uploader.status

    fun save(
        url: String,
        token: String,
        includeStoredAlerts: Boolean,
        useAsNode: Boolean,
        nowMillis: Long,
    ): Boolean {
        val target = normalizeDashboardUrl(url) ?: return false
        val since = if (includeStoredAlerts) 0L else nowMillis
        store.save(DashboardLink(target, token.trim(), since, useAsNode))
        return true
    }

    fun turnOff() = store.save(null)

    companion object {
        internal fun start(
            store: DashboardLinkStore,
            repository: MessageRepository,
            scope: CoroutineScope,
        ): DashboardConnection {
            val uploader = DashboardUploader(
                alerts = repository.sosAlerts.catch { emit(emptyList()) },
                contacts = repository.contacts.catch { emit(emptyList()) },
                link = store.link,
                post = ::postAlert,
            )
            scope.launch { uploader.run() }
            return DashboardConnection(store, uploader)
        }
    }
}

internal class DashboardLinkStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("dashboard_link", Context.MODE_PRIVATE)
    private val _link = MutableStateFlow(read())

    val link: StateFlow<DashboardLink?> = _link.asStateFlow()

    fun save(target: DashboardLink?) {
        preferences.edit().clear().apply {
            if (target != null) {
                putString("url", target.url)
                putString("token", target.token)
                putLong("since_millis", target.sinceMillis)
                putBoolean("use_as_node", target.useAsNode)
            }
        }.apply()
        _link.value = target
    }

    private fun read(): DashboardLink? {
        val url = preferences.getString("url", null) ?: return null
        val token = preferences.getString("token", "").orEmpty()
        return DashboardLink(
            url = url,
            token = token,
            sinceMillis = preferences.getLong("since_millis", 0L),
            useAsNode = preferences.getBoolean("use_as_node", false),
        )
    }
}

internal suspend fun postAlert(target: DashboardLink, body: String): UploadOutcome =
    withContext(Dispatchers.IO) {
        try {
            val connection = URL(target.url + "/api/alerts").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Upload-Token", target.token)
                connection.outputStream.use { it.write(body.toByteArray()) }
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> UploadOutcome.Accepted
                    HttpURLConnection.HTTP_BAD_REQUEST -> UploadOutcome.Refused
                    HttpURLConnection.HTTP_UNAUTHORIZED -> UploadOutcome.Unreachable("The upload token is wrong")
                    else -> UploadOutcome.Unreachable("The dashboard returned an error")
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            UploadOutcome.Unreachable("Can't reach the dashboard")
        }
    }
