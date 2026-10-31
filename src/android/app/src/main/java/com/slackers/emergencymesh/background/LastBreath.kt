package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.MessageBody

private const val DYING_PERCENT = 5

internal data class BatteryState(val levelPercent: Int, val plugged: Boolean)

/**
 * When a phone with an active SOS is about to die, it sends one final SOS while it still can. The
 * mesh already stores and forwards an SOS for 24 hours, so nearby phones carry these last words
 * after this phone shuts down. A phone that never asked for help stays quiet.
 */
internal class LastBreath(
    private val sosUntil: () -> Long,
    private val sentFor: () -> Long,
    private val markSent: (Long) -> Unit,
    private val alerts: suspend () -> List<ChatMessage>,
    private val send: suspend (MessageBody) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun onBattery(state: BatteryState) {
        val session = sosUntil()
        val dying = !state.plugged && state.levelPercent <= DYING_PERCENT
        if (!dying || session <= nowMillis() || sentFor() == session) return
        send(finalWords(state.levelPercent, alerts().filter { it.outgoing }))
        markSent(session)
    }

    private fun finalWords(levelPercent: Int, ownAlerts: List<ChatMessage>): MessageBody {
        val newest = ownAlerts.maxByOrNull { it.createdAtMillis }
        val located = ownAlerts
            .filter { it.body.latitude != null && it.body.longitude != null }
            .maxByOrNull { it.createdAtMillis }
            ?.body
        val notice = "Last message from this phone. Battery is at $levelPercent% and it will shut down soon."
        val text = newest?.let { "$notice Earlier message: ${it.body.text}" } ?: notice
        return MessageBody(
            text = text.take(MAX_TEXT_CHARS),
            latitude = located?.latitude,
            longitude = located?.longitude,
            accuracyMeters = located?.accuracyMeters,
            locationTimeMillis = located?.locationTimeMillis,
        )
    }
}
