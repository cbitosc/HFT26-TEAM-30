package com.slackers.emergencymesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.PacketType
import com.slackers.emergencymesh.core.Priority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat
import java.util.Date

@Composable
internal fun Notice(title: String, text: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ChatsScreen(
    contacts: List<ContactCard>,
    alerts: List<ChatMessage>,
    onOpenContact: (ContactCard) -> Unit,
    onInspect: (ContactCard) -> Unit,
    onNearby: () -> Unit,
    onSos: () -> Unit,
    onEnroll: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading("Stay in touch nearby", "Messages travel through nearby participating phones.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onNearby, modifier = Modifier.weight(1f)) { Text("Find devices") }
                OutlinedButton(onClick = onSos, modifier = Modifier.weight(1f)) { Text("Emergency SOS") }
            }
        }
        if (alerts.any { !it.outgoing }) item {
            Notice("Incoming SOS", "An emergency alert is available. Open SOS to read it.")
        }
        item { Text("CONTACTS", style = MaterialTheme.typography.labelLarge) }
        item { OutlinedButton(onClick = onEnroll) { Text("Enroll a contact") } }
        if (contacts.isEmpty()) item {
            Notice("No enrolled contacts", "Find nearby phones and enroll their public contact cards with your team. Verify fingerprints before sending direct messages.")
        }
        items(contacts, key = { it.deviceId }) { card ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(card.displayName.ifBlank { "Unnamed device" }, style = MaterialTheme.typography.titleMedium)
                    Text(card.deviceId, style = MaterialTheme.typography.bodySmall)
                    Text(if (card.verified) "Fingerprint verified" else "Fingerprint not verified",
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenContact(card) }) { Text("Open chat") }
                        TextButton(onClick = { onInspect(card) }) { Text("Inspect keys") }
                    }
                }
            }
        }
        item {
            Notice("When nobody is nearby", "Messages stay Pending until a useful device encounter occurs. Without a future path, delivery cannot be guaranteed.")
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun ConversationScreen(
    contact: ContactCard?,
    deviceId: String,
    messages: List<ChatMessage>,
    canSend: Boolean,
    sending: Boolean,
    submission: Int,
    onBack: () -> Unit,
    onInspect: () -> Unit,
    onSend: (String, Priority) -> Unit,
    observeStatusTimes: ((String) -> Flow<MessageStatusTimes?>)? = null,
) {
    var draft by rememberSaveable(deviceId) { mutableStateOf("") }
    var priorityName by rememberSaveable(deviceId) { mutableStateOf(Priority.P2_REGULAR.name) }
    val priority = Priority.valueOf(priorityName)
    var shownStatusId by remember(deviceId) { mutableStateOf<String?>(null) }
    var previousSubmission by remember(deviceId) { mutableStateOf(submission) }
    val listState = rememberLazyListState()
    val sorted = remember(messages) { messages.sortedWith(compareBy({ it.createdAtMillis }, { it.messageId })) }
    val validation = textError(draft)

    LaunchedEffect(submission) {
        if (submission != previousSubmission) {
            draft = ""
            previousSubmission = submission
        }
    }
    LaunchedEffect(sorted.lastOrNull()?.messageId) {
        if (sorted.isNotEmpty()) listState.animateScrollToItem(sorted.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(contact?.displayName ?: "Conversation", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onInspect, enabled = contact != null) { Text("Keys") }
        }
        if (contact?.verified != true) Notice("Verify this contact", "Direct sending is disabled until the contact's public-key fingerprint is compared and saved.")
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sorted.isEmpty()) item {
                Text("No messages yet. Queued messages will appear here from device storage.",
                    Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(sorted, key = { it.messageId }) { message ->
                MessageBubble(message, onStatus = { shownStatusId = message.messageId })
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Priority.P2_REGULAR, Priority.P1_EMERGENCY).forEach { option ->
                FilterChip(
                    selected = priority == option,
                    onClick = { priorityName = option.name },
                    label = { Text(priorityLabel(option)) },
                    enabled = !sending,
                )
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            enabled = !sending,
            minLines = 1,
            maxLines = 4,
            isError = draft.length > MAX_TEXT_CHARS,
            supportingText = { Text("${draft.length}/$MAX_TEXT_CHARS") },
        )
        Button(
            onClick = { if (validation == null) onSend(draft, priority) },
            enabled = canSend && !sending && validation == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (sending) "Saving…" else "Queue message") }
        Spacer(Modifier.height(8.dp))
    }

    messages.firstOrNull { it.messageId == shownStatusId }?.let { message ->
        val timesFlow = remember(message.messageId, observeStatusTimes) {
            (observeStatusTimes?.invoke(message.messageId) ?: flowOf<MessageStatusTimes?>(null))
                .catch { emit(null) }
        }
        val times by timesFlow.collectAsStateWithLifecycle(initialValue = null)
        AlertDialog(
            onDismissRequest = { shownStatusId = null },
            title = { Text(statusLabel(message.status)) },
            text = { MessageStatusTimeline(message, times) },
            confirmButton = { TextButton(onClick = { shownStatusId = null }) { Text("Done") } },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onStatus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp)
                .background(
                    if (message.outgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                ).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(priorityLabel(message.priority), style = MaterialTheme.typography.labelSmall)
            Text(message.body.text, style = MaterialTheme.typography.bodyLarge)
            Text(displayTime(message.createdAtMillis), style = MaterialTheme.typography.labelSmall)
            if (message.outgoing && message.type == PacketType.MESSAGE) {
                TextButton(onClick = onStatus) {
                    Text("${statusLabel(message.status)} · ${message.hopCount} hops")
                }
            } else Text("${message.hopCount} hops", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun SosScreen(
    alerts: List<ChatMessage>,
    sending: Boolean,
    canSend: Boolean,
    groupConfigured: Boolean,
    canConfigureGroup: Boolean,
    onConfigureGroup: () -> Unit,
    onRequestLocation: () -> Unit,
    onSend: (String, Boolean) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("I need help. Please respond if you are nearby.") }
    var includeLocation by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val validation = textError(draft)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("Emergency SOS", "Broadcast to participating enrolled devices.") }
        item { Notice("Highest queue priority", "An SOS can be carried by nearby participants. It does not directly contact emergency services, and receipt by everyone is not guaranteed.") }
        item {
            if (!groupConfigured) Notice("SOS group not configured", "Set the same group code on participating phones before sending or reading group SOS alerts.")
            TextButton(enabled = canConfigureGroup && !sending, onClick = onConfigureGroup) {
                Text(if (groupConfigured) "Change SOS group code" else "Set SOS group code")
            }
        }
        item {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What help do you need?") },
                minLines = 2,
                maxLines = 5,
                enabled = !sending,
                isError = draft.length > MAX_TEXT_CHARS,
                supportingText = { Text("${draft.length}/$MAX_TEXT_CHARS") },
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeLocation, onCheckedChange = { includeLocation = it }, enabled = !sending)
                Text("Attach a recent location if available", Modifier.weight(1f))
            }
            Text("Optional. Without permission or a fix from the last two minutes, SOS sends without coordinates.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRequestLocation, enabled = !sending) { Text("Allow location access") }
        }
        item {
            Button(
                onClick = { confirming = true },
                enabled = canSend && !sending && validation == null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(if (sending) "Saving SOS…" else "Review and send SOS") }
        }
        item { Text("SOS ACTIVITY", style = MaterialTheme.typography.labelLarge) }
        if (alerts.isEmpty()) item { Text("No stored SOS alerts.") }
        items(alerts.sortedByDescending { it.createdAtMillis }, key = { it.messageId }) { message ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (message.outgoing) "Your SOS · stored locally" else "Incoming SOS",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    Text(message.body.text)
                    Text("From: ${message.senderId}", style = MaterialTheme.typography.bodySmall)
                    Text("${displayTime(message.createdAtMillis)} · ${message.hopCount} hops",
                        style = MaterialTheme.typography.labelSmall)
                    val body = message.body
                    if (body.latitude != null && body.longitude != null) {
                        Text("Coordinates: ${body.latitude}, ${body.longitude}", style = MaterialTheme.typography.bodySmall)
                        body.accuracyMeters?.let { Text("Accuracy: ±${it.toInt()} m", style = MaterialTheme.typography.bodySmall) }
                        body.locationTimeMillis?.let { Text("Location recorded: ${displayTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    } else Text("Location unavailable or omitted", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Send emergency SOS?") },
        text = {
            Text("${draft.trim()}\n\n${if (includeLocation) "A recent available location will be attached." else "Location will not be attached."}\nEnrolled participants can read this alert.")
        },
        confirmButton = {
            Button(
                enabled = canSend && !sending && validation == null,
                onClick = { confirming = false; onSend(draft, includeLocation) },
            ) { Text("Send SOS") }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 18.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun displayTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
