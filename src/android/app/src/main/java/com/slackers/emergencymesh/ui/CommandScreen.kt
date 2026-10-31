package com.slackers.emergencymesh.ui

import android.location.Location
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
import com.slackers.emergencymesh.core.AreaAlert
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.compassDirection
import com.slackers.emergencymesh.core.distanceMeters
import com.slackers.emergencymesh.security.AppRole
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val DEFAULT_RADIUS_KM = "1"

/** A person's position in words, measured from this phone so it works with no maps loaded. */
internal fun whereToFind(message: ChatMessage, here: Location?): String {
    val body = message.body
    val latitude = body.latitude
    val longitude = body.longitude
    val hops = if (message.hopCount == 1) "1 hop" else "${message.hopCount} hops"
    if (latitude == null || longitude == null) return "No location sent. This alert came $hops to reach you."
    val position = "%.5f, %.5f".format(latitude, longitude)
    val accuracy = body.accuracyMeters?.let { " ±${it.roundToInt()} m" }.orEmpty()
    if (here == null) return "$position$accuracy. Set this phone's location to see the distance."
    val meters = distanceMeters(here.latitude, here.longitude, latitude, longitude)
    val distance = if (meters < 1_000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1_000)
    val direction = compassDirection(here.latitude, here.longitude, latitude, longitude)
    return "$distance $direction of you, at $position$accuracy"
}

@Composable
internal fun CommandScreen(
    role: AppRole,
    here: Location?,
    needHelp: List<ChatMessage>,
    sentAlerts: List<ChatMessage>,
    nameOf: (String) -> String,
    sending: Boolean,
    onRefreshLocation: () -> Unit,
    onSend: (AreaAlert) -> Unit,
) {
    var message by remember { mutableStateOf("Flood warning. Move to high ground now.") }
    var radiusKm by remember { mutableStateOf(DEFAULT_RADIUS_KM) }
    var latitude by remember(here) { mutableStateOf(here?.latitude?.toString().orEmpty()) }
    var longitude by remember(here) { mutableStateOf(here?.longitude?.toString().orEmpty()) }
    val alert = remember(message, radiusKm, latitude, longitude) {
        val radius = radiusKm.toFloatOrNull()?.times(1_000f)
        val lat = latitude.toDoubleOrNull()
        val lon = longitude.toDoubleOrNull()
        if (radius == null || lat == null || lon == null || message.length > MAX_TEXT_CHARS) null
        else AreaAlert.fromBody(AreaAlert(message.trim(), lat, lon, radius).toBody())
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("${role.title} controls", "These work with no cellular network.") }
        if (role == AppRole.ADMIN) {
            item { Text("WHERE PEOPLE NEED HELP", style = MaterialTheme.typography.labelLarge) }
            if (needHelp.isEmpty()) item { Text("No SOS alerts have reached this phone.") }
            items(needHelp, key = { it.messageId }) { person ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(nameOf(person.senderId), style = MaterialTheme.typography.titleSmall)
                        Text(whereToFind(person, here))
                        Text(person.body.text, style = MaterialTheme.typography.bodySmall)
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(person.createdAtMillis)),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { Text("WARN AN AREA", style = MaterialTheme.typography.labelLarge) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Every phone relays this, but only phones inside the area show it. A phone that doesn't know its location shows it anyway, and says so.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                    label = { Text("Message") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(latitude, { latitude = it }, Modifier.weight(1f), singleLine = true,
                        enabled = !sending, label = { Text("Latitude") })
                    OutlinedTextField(longitude, { longitude = it }, Modifier.weight(1f), singleLine = true,
                        enabled = !sending, label = { Text("Longitude") })
                }
                OutlinedTextField(radiusKm, { radiusKm = it }, Modifier.fillMaxWidth(), singleLine = true,
                    enabled = !sending, label = { Text("Radius in km") })
                TextButton(onClick = onRefreshLocation) { Text("Use this phone's location") }
                Button(
                    onClick = { alert?.let(onSend) },
                    enabled = alert != null && !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (sending) "Sending…" else "Send to this area") }
            }
        }
        if (sentAlerts.isNotEmpty()) item { Text("SENT FROM THIS PHONE", style = MaterialTheme.typography.labelLarge) }
        items(sentAlerts, key = { it.messageId }) { sent ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sent.body.text)
                    Text(areaLabel(sent), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

internal fun areaLabel(alert: ChatMessage): String {
    val body = alert.body
    val radius = body.accuracyMeters ?: return "Area unknown"
    return "Within %.1f km of %.4f, %.4f".format(radius / 1_000, body.latitude ?: 0.0, body.longitude ?: 0.0)
}

@Composable
internal fun TestLocationDialog(
    current: Pair<Double, Double>?,
    onSave: (Double, Double) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var latitude by remember { mutableStateOf(current?.first?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(current?.second?.toString().orEmpty()) }
    val lat = latitude.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lon = longitude.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For demos indoors, where GPS has no fix. Debug builds only.")
                OutlinedTextField(latitude, { latitude = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Latitude") })
                OutlinedTextField(longitude, { longitude = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Longitude") })
            }
        },
        confirmButton = {
            Button(enabled = lat != null && lon != null, onClick = {
                if (lat != null && lon != null) onSave(lat, lon)
            }) { Text("Use this location") }
        },
        dismissButton = {
            TextButton(onClick = if (current != null) onClear else onDismiss) {
                Text(if (current != null) "Use real location" else "Cancel")
            }
        },
    )
}
