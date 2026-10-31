package com.slackers.emergencymesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slackers.emergencymesh.background.REPLY_SECONDS

@Composable
internal fun NoAppReplyDialog(
    error: String?,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reply by remember { mutableStateOf("HELP COMING 10 MIN") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reply by Bluetooth name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("For $REPLY_SECONDS seconds this phone's Bluetooth name becomes your reply, so it appears in the Bluetooth list of nearby phones. Anyone nearby can read it.")
                Text("The mesh also uses the Bluetooth name, so this phone may find fewer phones until the reply ends.")
                OutlinedTextField(
                    value = reply,
                    onValueChange = { if (it.length <= 30) reply = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Reply, up to 30 characters") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = reply.isNotBlank(), onClick = { onSend(reply) }) { Text("Send reply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
