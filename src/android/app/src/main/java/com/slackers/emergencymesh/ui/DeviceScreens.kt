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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState

@Composable
internal fun NearbyScreen(
    peers: List<PeerInfo>,
    localCard: ContactCard?,
    fingerprint: String?,
    discoveryRequested: Boolean,
    canStart: Boolean,
    permissionFeedback: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRespond: (PeerInfo, Boolean) -> Unit,
    onSettings: () -> Unit,
    onCopyIdentity: () -> Unit,
) {
    var pairingEndpoint by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("Nearby devices", "Keep Wi-Fi and Bluetooth enabled. Internet is not required.") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localCard?.displayName ?: "Local identity not connected", style = MaterialTheme.typography.titleMedium)
                    Text("Device ID: ${localCard?.deviceId ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                    Text("Fingerprint: ${fingerprint ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCopyIdentity, enabled = localCard != null && fingerprint != null) {
                        Text("Copy ID and fingerprint")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = canStart && !discoveryRequested,
                    modifier = Modifier.weight(1f)) { Text("Start discovery") }
                OutlinedButton(onClick = onStop, enabled = canStart,
                    modifier = Modifier.weight(1f)) { Text("Stop") }
            }
            Text(if (discoveryRequested) "Discovery requested · check devices below for actual connections."
                else "Discovery not requested from this screen.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onSettings) { Text("App permissions and settings") }
        }
        permissionFeedback?.let { feedback -> item { Notice("Permission required", feedback) } }
        item { Notice("Before pairing", "Both people compare the connection token on their phones. Pair only if it matches. Contact-key enrollment is a separate step before direct messaging.") }
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
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionHeading("Debug log", "This session only · latest 200 events") }
        item { Notice("Current wiring", "$securityLabel\n$connectedCount connected peers reported by the transport.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy, enabled = logs.isNotEmpty()) { Text("Copy log") }
                OutlinedButton(onClick = onClear, enabled = logs.isNotEmpty()) { Text("Clear screen log") }
            }
        }
        item { Text("Clearing this screen does not delete messages, keys or the durable queue.", style = MaterialTheme.typography.bodySmall) }
        if (logs.isEmpty()) item { Text("No events in this session.") }
        items(logs.asReversed()) { entry ->
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
