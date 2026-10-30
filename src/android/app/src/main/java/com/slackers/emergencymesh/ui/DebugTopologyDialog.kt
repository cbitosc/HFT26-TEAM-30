package com.slackers.emergencymesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

internal fun topologyIds(text: String, localDeviceId: String?): Set<String> {
    val ids = text.split(Regex("[\\s,]+" )).filter { it.isNotBlank() }.toSet()
    require(ids.size <= 64) { "Use at most 64 device IDs." }
    require(ids.all { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }) { "Paste stable device IDs, separated by spaces or commas." }
    require(localDeviceId !in ids) { "Select other phones, not this phone's own ID." }
    return ids
}

@Composable
internal fun DebugTopologyDialog(
    allowed: Set<String>?,
    knownDevices: Map<String, String>,
    localDeviceId: String?,
    applyError: String?,
    onApply: (Set<String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(allowed.orEmpty()) }
    var pastedIds by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmBlockAll by remember { mutableStateOf(false) }
    val choices = (knownDevices.keys + selected).filter { it != localDeviceId }.sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug: forced topology") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This filter persists across restarts and can disconnect active peers. It controls radio links, not cryptographic trust.")
                Text("For A–B–C: A allows only B; C allows only B; B allows A and C. Apply on every phone. A blocked A–C link alone does not prove relay.")
                Text(when {
                    allowed == null -> "Current filter: off (unrestricted)"
                    allowed.isEmpty() -> "Current filter: all peers blocked"
                    else -> "Current filter: ${allowed.size} stable IDs allowed"
                }, style = MaterialTheme.typography.labelLarge)
                choices.forEach { id ->
                    Row {
                        Checkbox(checked = id in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + id else selected - id
                        })
                        Column(Modifier.padding(top = 8.dp)) {
                            Text(knownDevices[id] ?: "Saved or manually entered device")
                            Text(id, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = pastedIds,
                    onValueChange = { if (it.length <= 8_192) pastedIds = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3,
                    label = { Text("Additional stable device IDs") },
                )
                TextButton(onClick = {
                    try {
                        selected = topologyIds((selected + topologyIds(pastedIds, localDeviceId)).joinToString(" "), localDeviceId)
                        pastedIds = ""
                        error = null
                    } catch (failure: IllegalArgumentException) { error = failure.message }
                }, enabled = pastedIds.isNotBlank()) { Text("Add IDs to selection") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                applyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Selected: ${selected.size}. Only selected phones will be allowed after Apply.")
                OutlinedButton(onClick = { onApply(null) }) { Text("Clear filter · allow all") }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    topologyIds(selected.joinToString(" "), localDeviceId)
                    if (selected.isEmpty()) confirmBlockAll = true else onApply(selected)
                } catch (failure: IllegalArgumentException) { error = failure.message }
            }) { Text("Apply selection") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (confirmBlockAll) AlertDialog(
        onDismissRequest = { confirmBlockAll = false },
        title = { Text("Block every nearby peer?") },
        text = { Text("An empty allowlist disconnects all peers. To remove the filter, choose Clear filter instead.") },
        confirmButton = { Button(onClick = { confirmBlockAll = false; onApply(emptySet()) }) { Text("Block all peers") } },
        dismissButton = { TextButton(onClick = { confirmBlockAll = false }) { Text("Cancel") } },
    )
}
