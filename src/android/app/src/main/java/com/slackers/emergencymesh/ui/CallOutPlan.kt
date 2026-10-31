package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.background.Ringer
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.Priority
import kotlinx.coroutines.delay

/** Leaves a gap after each ring, so a rescuer can tell which phone they are hearing. */
internal const val CALL_OUT_SPACING_MILLIS = Ringer.RING_MILLIS + 2_000

/** Each phone that sent an SOS still inside its lifetime, newest first and listed once. */
internal fun callOutTargets(alerts: List<ChatMessage>, nowMillis: Long): List<String> {
    val oldest = nowMillis - Priority.P0_SOS.retentionSeconds * 1_000L
    return alerts
        .filter { !it.outgoing && it.createdAtMillis >= oldest }
        .sortedByDescending { it.createdAtMillis }
        .map { it.senderId }
        .distinct()
}

/** Sends one call at a time. The gap happens here, so phones don't need matching clocks. */
internal suspend fun callOutInTurn(
    targets: List<String>,
    spacingMillis: Long = CALL_OUT_SPACING_MILLIS,
    onTurn: (turn: Int, target: String) -> Unit,
    send: suspend (String) -> Unit,
) {
    targets.forEachIndexed { index, target ->
        if (index > 0) delay(spacingMillis)
        onTurn(index + 1, target)
        send(target)
    }
}
