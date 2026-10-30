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
import com.slackers.emergencymesh.core.ContactCard

@Composable
internal fun ContactEnrollmentDialog(
    localCard: ContactCard?,
    localDeviceId: String?,
    fingerprint: (ContactCard) -> String?,
    canEnroll: Boolean,
    saving: Boolean,
    saveError: String?,
    onCopy: (String) -> Unit,
    onEnroll: (ContactCard, Boolean) -> Unit,
    onDismiss: () -> Unit,
    groupConfigured: Boolean,
    canConfigureGroup: Boolean,
    onConfigureGroup: () -> Unit,
) {
    var pasted by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf<ContactCard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var compared by remember { mutableStateOf(false) }
    val export = remember(localCard) { localCard?.let { runCatching { ContactEnrollmentCodec.encode(it) }.getOrNull() } }
    val remoteFingerprint = remember(imported) { imported?.let(fingerprint) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Enroll a contact") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Share your public card", style = MaterialTheme.typography.titleSmall)
                Text("Exchange this transfer text through an agreed local method. It contains public keys only; it does not send or require a cloud request.")
                OutlinedButton(enabled = export != null && !saving,
                    onClick = { export?.let(onCopy) }) { Text("Copy my public contact card") }
                if (export == null) Text("Local signing/encryption identity is not connected yet.")
                localCard?.let { card ->
                    Text("My fingerprint: ${fingerprint(card) ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                }
                Text("2. Import their public card", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = pasted,
                    onValueChange = {
                        if (it.length <= ContactEnrollmentCodec.MAX_TEXT_LENGTH) pasted = it
                        imported = null; compared = false; error = null
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("Paste EMESH-CONTACT-1 text") },
                )
                TextButton(enabled = pasted.isNotBlank() && !saving, onClick = {
                    try {
                        val card = ContactEnrollmentCodec.decode(pasted)
                        require(card.deviceId != localDeviceId) { "This is your own contact card." }
                        imported = card
                        compared = false
                        error = null
                    } catch (failure: IllegalArgumentException) {
                        imported = null
                        error = failure.message ?: "Invalid contact card."
                    }
                }) { Text("Preview contact") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                imported?.let { card ->
                    Text("Name: ${card.displayName}")
                    Text("Device ID: ${card.deviceId}", style = MaterialTheme.typography.bodySmall)
                    Text("Their contact fingerprint: ${remoteFingerprint ?: "Security implementation not available"}")
                    Text("Compare this fingerprint with the other person's own screen through a trusted channel. Importing a card or matching a phone name does not verify it.")
                    Row {
                        Checkbox(checked = compared,
                            onCheckedChange = { compared = it },
                            enabled = !remoteFingerprint.isNullOrBlank() && !saving)
                        Text("I compared the full fingerprints and they match.", Modifier.padding(top = 8.dp))
                    }
                    OutlinedButton(enabled = canEnroll && !saving,
                        onClick = { onEnroll(card, false) }) { Text("Save as unverified") }
                }
                if (!canEnroll) Text("Saving waits for the real repository and security implementation.",
                    style = MaterialTheme.typography.bodySmall)
                Text("SOS group: ${if (groupConfigured) "configured" else "not configured"}")
                TextButton(enabled = canConfigureGroup && !saving, onClick = onConfigureGroup) {
                    Text(if (groupConfigured) "Change SOS group code" else "Set SOS group code")
                }
            }
        },
        confirmButton = {
            Button(enabled = canEnroll && imported != null && compared &&
                !remoteFingerprint.isNullOrBlank() && !saving,
                onClick = { imported?.let { onEnroll(it, true) } }) {
                Text(if (saving) "Saving…" else "Verify and enroll")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Close") } },
    )
}
