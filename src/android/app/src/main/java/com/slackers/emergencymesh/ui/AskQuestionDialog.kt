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

private const val MAX_QUESTION_CHARS = 200

@Composable
internal fun AskQuestionDialog(
    name: String,
    error: String?,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var question by remember { mutableStateOf("Are you hurt?") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("If its SOS is active, the phone reads this aloud. If its owner allowed it, the phone then listens once and sends back what it hears. Type in English, Hindi or Telugu.")
                OutlinedTextField(
                    value = question,
                    onValueChange = { if (it.length <= MAX_QUESTION_CHARS) question = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Short question") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = question.isNotBlank(), onClick = { onSend(question.trim()) }) { Text("Send question") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
