package com.slackers.emergencymesh.background

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MAX_WORDS_CHARS = 64
private const val MAX_REPLY_CHARS = 30
private const val REPEAT_AFTER_MILLIS = 30 * 60 * 1_000L
private const val MAX_ALERTS_PER_HOUR = 10
private const val HOUR_MILLIS = 60 * 60 * 1_000L
private const val SAVED_NAME = "name_before_reply"

/** How long a reply name stays up. Android's discoverable request uses the same length. */
internal const val REPLY_SECONDS = 120

/** How often to scan while listening. A scan lasts about 12 seconds and slows Bluetooth links. */
internal const val NO_APP_SCAN_MILLIS = 90_000L

internal data class HeardName(val address: String, val name: String?)

private val SOS_NAME = Regex("""^\s*sos(?:[\s:_.,!-]+(.*))?$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Returns what a person wrote after SOS, or null if this name isn't asking for help. */
internal fun noAppSosWords(bluetoothName: String?): String? {
    val match = SOS_NAME.matchEntire(bluetoothName ?: return null) ?: return null
    return match.groupValues[1].filterNot { it.isISOControl() }.trim().take(MAX_WORDS_CHARS)
}

/** Anyone can set a Bluetooth name, so the alert always says that nobody has verified it. */
internal fun noAppSosText(words: String): String {
    val request = if (words.isEmpty()) "It gave no details." else "It says: \"$words\"."
    return "No-app SOS, not verified. A nearby phone without EmergencyMesh is asking for help " +
        "over Bluetooth. $request It is within Bluetooth range of the phone that heard it."
}

/**
 * Decides which heard names become alerts. A Bluetooth name is open to anyone nearby, and each
 * alert travels at SOS priority, so this limit stops a prank from crowding out real alerts.
 */
internal class NoAppSosDetector(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val lastAlert = HashMap<String, Long>()
    private val recentAlerts = ArrayDeque<Long>()

    /** Returns the alert text, or null if there is nothing new to send. */
    fun heard(address: String, name: String?): String? {
        val words = noAppSosWords(name) ?: return null
        val now = nowMillis()
        val key = "$address|$words"
        val previous = lastAlert[key]
        if (previous != null && now - previous < REPEAT_AFTER_MILLIS) return null
        while (recentAlerts.isNotEmpty() && now - recentAlerts.first() >= HOUR_MILLIS) {
            recentAlerts.removeFirst()
        }
        if (recentAlerts.size >= MAX_ALERTS_PER_HOUR) return null
        lastAlert[key] = now
        recentAlerts.addLast(now)
        return noAppSosText(words)
    }
}

/** What the screens use to turn listening on, and to answer by Bluetooth name. */
class NoAppSosControl internal constructor(context: Context, private val scope: CoroutineScope) {
    private val preferences = context.applicationContext
        .getSharedPreferences("no_app_sos", Context.MODE_PRIVATE)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _listening = MutableStateFlow(preferences.getBoolean("listening", false))
    private val _alertsSent = MutableStateFlow(0)
    private var replyJob: Job? = null

    /** Scanning uses battery and slows Bluetooth links, so it stays off until someone asks. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    val alertsSent: StateFlow<Int> = _alertsSent.asStateFlow()

    init {
        // A reply can outlive the app, so put the phone's own name back at the next start.
        endReply()
    }

    fun setListening(on: Boolean) {
        preferences.edit().putBoolean("listening", on).apply()
        _listening.value = on
    }

    internal fun alertSent() {
        _alertsSent.value += 1
    }

    /** The mesh can't start without Bluetooth permissions, and this only runs while it's on. */
    @SuppressLint("MissingPermission")
    internal fun scanOnce() {
        val bluetooth = adapter ?: return
        // Never cancel here. That would also end the scan Nearby is running.
        runCatching { if (bluetooth.isEnabled) bluetooth.startDiscovery() }
    }

    /** Returns false if Android refused, so the caller can say so instead of waiting. */
    @SuppressLint("MissingPermission")
    fun beginReply(text: String): Boolean {
        val bluetooth = adapter ?: return false
        return runCatching {
            if (!preferences.contains(SAVED_NAME)) {
                val ownName = bluetooth.name ?: return false
                preferences.edit().putString(SAVED_NAME, ownName).commit()
            }
            val renamed = bluetooth.setName(text.trim().take(MAX_REPLY_CHARS))
            if (renamed) {
                replyJob?.cancel()
                replyJob = scope.launch {
                    delay(REPLY_SECONDS * 1_000L)
                    endReply()
                }
            } else {
                endReply()
            }
            renamed
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun endReply() {
        replyJob?.cancel()
        val ownName = preferences.getString(SAVED_NAME, null) ?: return
        val restored = runCatching { adapter?.setName(ownName) == true }.getOrDefault(false)
        if (restored) preferences.edit().remove(SAVED_NAME).apply()
    }
}
