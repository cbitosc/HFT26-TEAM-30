package com.slackers.emergencymesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.MessageStatus
import java.text.DateFormat
import java.util.Date

/** UI projection of persisted engine times. Never fill gaps from the current status or UI clock. */
data class MessageStatusTimes(
    val sentAtMillis: Long? = null,
    val relayedAtMillis: Long? = null,
    val deliveredAtMillis: Long? = null,
)

@Composable
internal fun MessageStatusTimeline(message: ChatMessage, times: MessageStatusTimes?) {
    Column(Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(statusExplanation(message.status))
        Text("STATUS TIMELINE", style = MaterialTheme.typography.labelLarge)
        val entries = listOf(
            MessageStatus.PENDING to message.createdAtMillis,
            MessageStatus.SENT to times?.sentAtMillis,
            MessageStatus.RELAYED to times?.relayedAtMillis,
            MessageStatus.DELIVERED to times?.deliveredAtMillis,
        )
        entries.forEach { (status, time) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(statusLabel(status), style = MaterialTheme.typography.titleSmall)
                Text(time?.let {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
                } ?: "No recorded time", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Times are recorded on this phone. Missing stages are not inferred from the current status.",
            style = MaterialTheme.typography.bodySmall)
        if (times == null) Text("Acknowledgement times are not available in this build.",
            style = MaterialTheme.typography.bodySmall)
    }
}
