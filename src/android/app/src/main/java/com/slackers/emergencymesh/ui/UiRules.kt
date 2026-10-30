package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.MessageStatus
import com.slackers.emergencymesh.core.Priority

/** UI validation only; the repository must enforce the same limits independently. */
internal fun textError(text: String): String? = when {
    text.isBlank() -> "Enter a message."
    text.length > MAX_TEXT_CHARS -> "Use $MAX_TEXT_CHARS characters or fewer."
    else -> null
}

internal fun priorityLabel(priority: Priority): String = when (priority) {
    Priority.P0_SOS -> "SOS"
    Priority.P1_EMERGENCY -> "Emergency"
    Priority.P2_REGULAR -> "Regular"
}

internal fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> "Pending"
    MessageStatus.SENT -> "Sent"
    MessageStatus.RELAYED -> "Relayed"
    MessageStatus.DELIVERED -> "Delivered"
}

internal fun statusExplanation(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> "Stored locally; waiting for a useful encounter or recipient key."
    MessageStatus.SENT -> "An immediate peer acknowledged receiving this message."
    MessageStatus.RELAYED -> "An intermediate phone confirmed onward sending."
    MessageStatus.DELIVERED -> "The recipient acknowledged receiving this message."
}
