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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun GroupCodeDialog(
    configured: Boolean,
    saving: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Intentionally not rememberSaveable: a secret is never written to saved UI state.
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (configured) "Change SOS group code" else "Set SOS group code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the same code on every participating phone. Anyone with the code can read group SOS alerts; direct messages use the recipient's verified public keys.")
                Text("Use at least 8 characters, preferably a long unrelated phrase. Share it separately from public contact cards.")
                if (configured) Text("Changing the code can make earlier SOS alerts unreadable on this phone.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 256) code = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saving,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("SOS group code") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !saving && code.trim().length >= 8,
                onClick = { onSave(code) }) { Text(if (saving) "Saving…" else "Save group code") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}
