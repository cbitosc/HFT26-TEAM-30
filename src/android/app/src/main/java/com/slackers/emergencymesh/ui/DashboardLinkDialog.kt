package com.slackers.emergencymesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.slackers.emergencymesh.background.DashboardLink

@Composable
internal fun DashboardLinkDialog(
    current: DashboardLink?,
    error: String?,
    onSave: (url: String, token: String, includeStoredAlerts: Boolean, useAsNode: Boolean) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(current?.url.orEmpty()) }
    // Intentionally not rememberSaveable: a secret is never written to saved UI state.
    var token by remember { mutableStateOf(current?.token.orEmpty()) }
    var includeStoredAlerts by remember { mutableStateOf(false) }
    var useAsNode by remember { mutableStateOf(current?.useAsNode == true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dashboard upload") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("When this phone can reach the dashboard, it uploads the SOS alerts it holds, including their text and location. Direct messages stay on the phone. The mesh works the same without this.")
                Text("Plain http:// isn't encrypted, so anyone on the same network can read uploads. Use a private hotspot.")
                OutlinedTextField(
                    value = url,
                    onValueChange = { if (it.length <= 256) url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Dashboard address") },
                    placeholder = { Text("http://192.168.1.5:8080") },
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { if (it.length <= 256) token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Upload token") },
                )
                Row {
                    Checkbox(checked = includeStoredAlerts, onCheckedChange = { includeStoredAlerts = it })
                    Text("Also upload older alerts stored on this phone", Modifier.padding(top = 12.dp))
                }
                Row {
                    Checkbox(checked = useAsNode, onCheckedChange = { useAsNode = it })
                    Text("Also carry mesh messages through this laptop", Modifier.padding(top = 12.dp))
                }
                if (useAsNode) Text("The laptop can't read what it carries, but it can see which phones are talking.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = url.isNotBlank() && token.isNotBlank(),
                onClick = { onSave(url, token, includeStoredAlerts, useAsNode) }) { Text("Turn on") }
        },
        dismissButton = {
            if (current != null) TextButton(onClick = onTurnOff) { Text("Turn off") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
