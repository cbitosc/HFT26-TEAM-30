package com.slackers.emergencymesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.transport.LocalDevice

@Composable
internal fun NearbyScreen(
    peers: List<PeerInfo>,
    localDevice: LocalDevice?,
    fingerprint: String?,
    discoveryRequested: Boolean,
    keptAlive: Boolean,
    startingConnection: Boolean,
    canStart: Boolean,
    permissionFeedback: String?,
    radioProblems: List<String>,
    batteryRestricted: Boolean,
    discoveryMode: DiscoveryMode,
    canRename: Boolean,
    onRename: (String) -> Unit,
    onRecheck: () -> Unit,
    onMode: (DiscoveryMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRespond: (PeerInfo, Boolean) -> Unit,
    onAllowBackground: () -> Unit,
    onSettings: () -> Unit,
    onCopyIdentity: () -> Unit,
    onEnroll: () -> Unit,
) {
    var pairingEndpoint by remember { mutableStateOf<String?>(null) }
    var name by remember(localDevice?.deviceId, localDevice?.displayName) {
        mutableStateOf(localDevice?.displayName.orEmpty())
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("Nearby devices", "Keep Wi-Fi and Bluetooth enabled. Internet is not required.") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localDevice?.displayName ?: "Local device not connected", style = MaterialTheme.typography.titleMedium)
                    Text("Device ID: ${localDevice?.deviceId ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                    Text("Signing fingerprint: ${fingerprint ?: "Not enrolled. This ID identifies the radio only."}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCopyIdentity, enabled = localDevice != null) {
                        Text("Copy device details")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 32) name = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = canRename,
                singleLine = true,
                label = { Text("Phone name (for example A, B or C)") },
                supportingText = { Text("Stop discovery before renaming. Signing identities are managed during enrollment.") },
            )
            TextButton(onClick = { onRename(name) },
                enabled = canRename && name.isNotBlank() && name.trim() != localDevice?.displayName) {
                Text("Save phone name")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = canStart && !discoveryRequested && !startingConnection,
                    modifier = Modifier.weight(1f)) { Text(if (startingConnection) "Starting…" else "Start discovery") }
                OutlinedButton(onClick = onStop, enabled = canStart,
                    modifier = Modifier.weight(1f)) { Text("Stop") }
            }
            Text(when {
                discoveryRequested && keptAlive -> "Mesh on · it keeps relaying while this phone is locked. Stop it here or from the notification."
                discoveryRequested -> "Mesh on · Android refused background running, so keep this app open."
                else -> "Mesh off · start discovery to send and relay messages."
            }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onSettings) { Text("App permissions and settings") }
            TextButton(onClick = onRecheck) { Text("Recheck device readiness") }
        }
        items(radioProblems) { problem -> Notice("Device readiness", problem) }
        if (batteryRestricted) item {
            Notice("Background relay", "Battery optimization can stop relaying while this phone is locked.")
            TextButton(onClick = onAllowBackground) { Text("Allow background relay") }
        }
        item {
            Text("DISCOVERY MODE", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscoveryMode.entries.forEach { mode ->
                    FilterChip(
                        selected = discoveryMode == mode,
                        onClick = { onMode(mode) },
                        enabled = canStart,
                        label = { Text(if (mode == DiscoveryMode.BALANCED) "Balanced" else "Continuous") },
                    )
                }
            }
            Text("Balanced scans in bursts. Continuous keeps scanning; the engine will manage SOS mode when integrated.",
                style = MaterialTheme.typography.bodySmall)
        }
        permissionFeedback?.let { feedback -> item { Notice("Permission required", feedback) } }
        item { Notice("Before pairing", "Both people compare the connection token on their phones. Pair only if it matches. Contact-key enrollment is a separate step before direct messaging.") }
        item { OutlinedButton(onClick = onEnroll) { Text("Enroll or share a contact") } }
        if (peers.isEmpty()) item {
            Notice("No devices reported", "Start discovery on another participating phone. Check permissions and radios if it does not appear.")
        }
        items(peers, key = { it.endpointId }) { peer ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(peer.displayName.ifBlank { "Unnamed device" }, style = MaterialTheme.typography.titleMedium)
                    Text(peer.deviceId, style = MaterialTheme.typography.bodySmall)
                    Text(peerStateLabel(peer.state), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                    if (peer.state == PeerState.AWAITING_APPROVAL) {
                        Button(onClick = { pairingEndpoint = peer.endpointId }) { Text("Review connection") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    // Look up the current peer each recomposition so a stale dialog cannot approve
    // a disconnected endpoint or a replaced verification token.
    val pairingPeer = peers.firstOrNull {
        it.endpointId == pairingEndpoint && it.state == PeerState.AWAITING_APPROVAL
    }
    pairingPeer?.let { peer ->
        var compared by remember(peer.endpointId, peer.authToken) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pairingEndpoint = null },
            title = { Text("Compare with ${peer.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(peer.authToken?.takeIf { it.isNotBlank() } ?: "No verification token available.",
                        style = MaterialTheme.typography.headlineSmall)
                    Text("The other phone must show the same token. Reject the connection if it differs.")
                    Row {
                        Checkbox(checked = compared, onCheckedChange = { compared = it })
                        Text("I compared both phones; the tokens match.", Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                Button(enabled = compared && !peer.authToken.isNullOrBlank(), onClick = {
                    onRespond(peer, true)
                    pairingEndpoint = null
                }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { onRespond(peer, false); pairingEndpoint = null }) { Text("Reject") }
            },
        )
    }
}

@Composable
internal fun DebugScreen(
    logs: List<String>,
    connectedCount: Int,
    securityLabel: String,
    storageLabel: String,
    radioLog: List<String>,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    showTopologyTools: Boolean,
    allowlistSummary: String,
    onTopology: () -> Unit,
    dashboardStatus: String?,
    onDashboard: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionHeading("Debug log", "This session only · latest 200 events") }
        item { Notice("Current wiring", "$securityLabel\n$storageLabel\n$connectedCount connected peers reported by the transport.") }
        if (showTopologyTools) item {
            Text("DEBUG TOPOLOGY", style = MaterialTheme.typography.labelLarge)
            Text(allowlistSummary, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onTopology) { Text("Configure allowed devices") }
        }
        dashboardStatus?.let { status ->
            item {
                Text("DASHBOARD UPLOAD", style = MaterialTheme.typography.labelLarge)
                Text(status, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onDashboard) { Text("Set up dashboard upload") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy, enabled = logs.isNotEmpty() || radioLog.isNotEmpty()) { Text("Copy logs") }
                OutlinedButton(onClick = onClear, enabled = logs.isNotEmpty()) { Text("Clear app log") }
            }
        }
        item { Text("Clear app log affects this session's UI events only. Radio history, messages, keys and the durable queue remain.", style = MaterialTheme.typography.bodySmall) }
        if (logs.isEmpty()) item { Text("No events in this session.") }
        items(logs.asReversed()) { entry ->
            Text(entry, style = MaterialTheme.typography.bodySmall)
        }
        item { Text("RADIO HISTORY", style = MaterialTheme.typography.labelLarge) }
        if (radioLog.isEmpty()) item { Text("No radio events reported yet.") }
        items(radioLog.asReversed()) { entry ->
            Text(entry, style = MaterialTheme.typography.bodySmall)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun peerStateLabel(state: PeerState): String = when (state) {
    PeerState.DISCOVERED -> "Discovered"
    PeerState.CONNECTING -> "Connecting"
    PeerState.AWAITING_APPROVAL -> "Awaiting your approval"
    PeerState.CONNECTED -> "Connected"
    PeerState.DISCONNECTED -> "Disconnected"
}
