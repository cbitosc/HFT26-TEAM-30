package com.slackers.emergencymesh.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.slackers.emergencymesh.core.ChatMessage
import com.slackers.emergencymesh.core.ContactCard
import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.PeerInfo
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.core.TransportEvent
import com.slackers.emergencymesh.transport.LocalDevice
import com.slackers.emergencymesh.transport.LocalDeviceStore
import com.slackers.emergencymesh.transport.RadioProblem
import com.slackers.emergencymesh.transport.RadioReadiness
import com.slackers.emergencymesh.transport.TransportLogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppTab(val title: String) {
    CHATS("Chats"), NEARBY("Nearby"), SOS("SOS"), DEBUG("Debug")
}

@Composable
fun EmergencyMeshApp(services: AppServices? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tabName by rememberSaveable { mutableStateOf(AppTab.CHATS.name) }
    var conversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = AppTab.valueOf(tabName)
    var discoveryRequested by remember { mutableStateOf(false) }
    var sendingDirect by remember { mutableStateOf(false) }
    var sendingSos by remember { mutableStateOf(false) }
    var directSubmission by remember { mutableStateOf(0) }
    var submittedConversationId by remember { mutableStateOf<String?>(null) }
    var inspectedContact by remember { mutableStateOf<ContactCard?>(null) }
    var permissionFeedback by remember { mutableStateOf<String?>(null) }
    var device by remember(services) { mutableStateOf(services?.device) }
    var discoveryMode by remember(services) { mutableStateOf(DiscoveryMode.BALANCED) }
    var radioProblems by remember(context) { mutableStateOf(RadioReadiness.check(context)) }
    val log = remember { mutableStateListOf<String>() }

    fun record(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        log.add("$time  $message")
        while (log.size > 200) log.removeAt(0)
    }

    fun notifyUser(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    val localIdentityResult = remember(services) {
        runCatching { services?.security?.localContactCard() }
    }
    val localCard = localIdentityResult.getOrNull()
    val localFingerprint = remember(services, localCard) {
        localCard?.let { card -> runCatching { services?.security?.fingerprint(card) }.getOrNull() }
    }
    val identityError = localIdentityResult.exceptionOrNull()?.message
    val transportDevice = localCard?.let { LocalDevice(it.deviceId, it.displayName) } ?: device
    val canMessage = services?.repository != null && localCard != null
    val storageLabel = when {
        services?.repository != null -> "Message repository attached"
        services?.storedMessages != null -> "Room attached · read only"
        else -> "Storage not connected"
    }

    val contactsFlow = remember(services) {
        (services?.repository?.contacts ?: services?.storedMessages?.contacts
            ?: flowOf(emptyList<ContactCard>())).catch { error ->
            record("Contact storage failed: ${error.message ?: "unknown error"}")
            notifyUser("Could not load contacts. Check Debug.")
            emit(emptyList())
        }
    }
    val alertsFlow = remember(services) {
        (services?.repository?.sosAlerts ?: services?.storedMessages?.sosAlerts
            ?: flowOf(emptyList<ChatMessage>())).catch { error ->
            record("SOS storage failed: ${error.message ?: "unknown error"}")
            notifyUser("Could not load SOS alerts. Check Debug.")
            emit(emptyList())
        }
    }
    val conversationFlow = remember(services, conversationId) {
        (conversationId?.let { services?.repository?.conversation(it)
            ?: services?.storedMessages?.conversation(it) }
            ?: flowOf(emptyList<ChatMessage>())).catch { error ->
            record("Conversation storage failed: ${error.message ?: "unknown error"}")
            notifyUser("Could not load this conversation. Check Debug.")
            emit(emptyList())
        }
    }
    val contacts by contactsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val alerts by alertsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val messages by conversationFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val peersFlow = remember(services) { services?.transport?.peers ?: flowOf(emptyList<PeerInfo>()) }
    val peers by peersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val radioLogFlow = remember(services) { services?.radioLog ?: flowOf(emptyList<TransportLogEntry>()) }
    val radioEntries by radioLogFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val connected = peers.count { it.state == PeerState.CONNECTED }
    val securityLabel = when {
        services == null -> "Services not connected"
        services.security == null -> "Radio setup only · encryption not connected"
        localCard == null -> "Identity unavailable"
        services.security?.isDevelopmentPlaintext == true -> "Development plaintext · not secure"
        else -> "Security provider loaded"
    }

    LaunchedEffect(services) {
        record(if (services == null) "UI ready; real services not wired."
            else "Real transport supplied. $storageLabel.")
        services?.transport?.events?.collect { event ->
            when (event) {
                is TransportEvent.PeerConnected -> record("Connected: ${event.peer.deviceId}")
                is TransportEvent.PeerDisconnected -> record("Disconnected: ${event.deviceId}")
                is TransportEvent.PacketReceived -> record(
                    "Received ${event.packet.type} ${event.packet.id} via ${event.fromEndpointId}; hop ${event.packet.hopCount}"
                )
                is TransportEvent.MalformedData -> record(
                    "Rejected data from ${event.fromEndpointId}: ${event.reason}"
                )
                is TransportEvent.Failure -> {
                    runCatching { services?.transport?.stop() }
                    discoveryRequested = false
                    record("Transport failure: ${event.reason}")
                    notifyUser(event.reason)
                }
            }
        }
    }

    // No foreground service is part of this milestone. Stop radio work on background
    // and require the user to start it again on returning to the app.
    DisposableEffect(services, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) radioProblems = RadioReadiness.check(context)
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { services?.transport?.stop() }
                discoveryRequested = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { services?.transport?.stop() }
        }
    }

    fun startDiscovery() {
        val active = services
        val radioDevice = transportDevice
        if (active == null || radioDevice == null) {
            notifyUser("A real transport and device ID must be wired first.")
            return
        }
        if (active.security != null && localCard == null) {
            notifyUser("The security identity could not be loaded. Check Debug before starting.")
            return
        }
        radioProblems = RadioReadiness.check(context)
        if (missingNearbyPermissions(context).isNotEmpty()) {
            permissionFeedback = "Nearby access is incomplete. Allow the requested permissions or open App settings."
            return
        }
        if (radioProblems.any { it is RadioProblem.PlayServicesUnavailable }) {
            permissionFeedback = "Google Play services must be available before starting discovery."
            return
        }
        try {
            active.transport.start(radioDevice.deviceId, radioDevice.displayName)
            discoveryRequested = true
            permissionFeedback = null
            record("Discovery requested as ${radioDevice.deviceId}. No message engine is started by the UI.")
        } catch (error: Exception) {
            record("Start failed: ${error.message}")
            notifyUser("Could not start discovery. Check Debug.")
        }
    }

    val nearbyPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> startDiscovery() }

    val sosLocationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        notifyUser(
            if (result.values.any { it }) "Location access updated. A recent fix will be used if available."
            else "Location denied. You can still send SOS without coordinates."
        )
    }

    BackHandler(enabled = conversationId != null) { conversationId = null }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("EmergencyMesh", style = MaterialTheme.typography.titleLarge)
                        Text(securityLabel, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("$connected connected", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    AppTab.entries.forEach { item ->
                        TextButton(
                            onClick = { conversationId = null; tabName = item.name },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                item.title,
                                color = if (tab == item) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!canMessage) {
                Notice(
                    if (services == null) "Integration pending" else "Connections ready · messaging pending",
                    identityError ?: if (services == null) "No services are wired. No messages will be sent."
                    else "$storageLabel. Nearby pairing is available. Message engine and security must be connected before sending.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val currentId = conversationId
            if (currentId != null) {
                val contact = contacts.firstOrNull { it.deviceId == currentId }
                ConversationScreen(
                    contact = contact,
                    deviceId = currentId,
                    messages = messages,
                    canSend = canMessage && contact?.verified == true,
                    sending = sendingDirect,
                    submission = if (submittedConversationId == currentId) directSubmission else 0,
                    onBack = { conversationId = null },
                    onInspect = { inspectedContact = contact },
                    onSend = { text, priority ->
                        val active = services
                        val repository = active?.repository
                        if (repository != null && canMessage && !sendingDirect && contact?.verified == true) {
                            sendingDirect = true
                            scope.launch {
                                try {
                                    val messageId = withContext(Dispatchers.IO) {
                                        repository.sendDirect(currentId, text.trim(), priority)
                                    }
                                    record("Direct message stored: $messageId")
                                    submittedConversationId = currentId
                                    directSubmission++
                                    notifyUser("Queued locally. Delivery waits for a recipient acknowledgement.")
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    record("Send failed: ${error.message}")
                                    notifyUser("Message was not confirmed stored. Check Debug; your draft is kept.")
                                } finally {
                                    sendingDirect = false
                                }
                            }
                        }
                    },
                )
            } else when (tab) {
                AppTab.CHATS -> ChatsScreen(
                    contacts = contacts,
                    alerts = alerts,
                    onOpenContact = { conversationId = it.deviceId },
                    onInspect = { inspectedContact = it },
                    onNearby = { tabName = AppTab.NEARBY.name },
                    onSos = { tabName = AppTab.SOS.name },
                )
                AppTab.NEARBY -> NearbyScreen(
                    peers = peers,
                    localDevice = transportDevice,
                    fingerprint = localFingerprint,
                    discoveryRequested = discoveryRequested,
                    canStart = services != null && transportDevice != null &&
                        (services.security == null || localCard != null),
                    permissionFeedback = permissionFeedback,
                    radioProblems = radioProblems.map(::radioProblemLabel),
                    discoveryMode = discoveryMode,
                    canRename = services != null && services.security == null && !discoveryRequested,
                    onRename = { name ->
                        val current = device
                        if (current != null && name.isNotBlank() && services?.security == null && !discoveryRequested) {
                            LocalDeviceStore.saveDisplayName(context, name.trim())
                            device = current.copy(displayName = name.trim())
                            record("Display name saved.")
                        }
                    },
                    onRecheck = { radioProblems = RadioReadiness.check(context) },
                    onMode = { mode ->
                        try {
                            services?.transport?.setDiscoveryMode(mode)
                            discoveryMode = mode
                            record("Discovery mode selected: $mode")
                        } catch (error: Exception) {
                            notifyUser("Could not change discovery mode: ${error.message}")
                        }
                    },
                    onStart = {
                        if (missingNearbyPermissions(context).isEmpty()) startDiscovery()
                        else nearbyPermissionRequest.launch(nearbyPermissions().toTypedArray())
                    },
                    onStop = {
                        try {
                            services?.transport?.stop()
                            discoveryRequested = false
                            record("Discovery stopped by user.")
                        } catch (error: Exception) {
                            notifyUser("Could not stop transport: ${error.message}")
                        }
                    },
                    onRespond = { peer, accept ->
                        try {
                            services?.transport?.respondToConnection(peer.endpointId, accept)
                            record("Pairing ${if (accept) "approved" else "rejected"}: ${peer.deviceId}")
                        } catch (error: Exception) {
                            notifyUser("Pairing failed: ${error.message}")
                        }
                    },
                    onSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    },
                    onCopyIdentity = {
                        clipboard.setText(AnnotatedString("${transportDevice?.deviceId.orEmpty()}\n${localFingerprint ?: "Signing fingerprint not available; radio identity only."}"))
                        notifyUser("Device details copied. Pairing alone does not verify signing keys.")
                    },
                )
                AppTab.SOS -> SosScreen(
                    alerts = alerts,
                    sending = sendingSos,
                    canSend = canMessage,
                    onRequestLocation = {
                        sosLocationRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION))
                    },
                    onSend = { text, includeLocation ->
                        val active = services
                        val repository = active?.repository
                        if (repository != null && canMessage && !sendingSos) {
                            sendingSos = true
                            scope.launch {
                                try {
                                    val location = if (includeLocation) withContext(Dispatchers.IO) {
                                        recentLocation(context)
                                    } else null
                                    val body = MessageBody(
                                        text = text.trim(),
                                        latitude = location?.latitude,
                                        longitude = location?.longitude,
                                        accuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy,
                                        locationTimeMillis = location?.time,
                                    )
                                    val id = withContext(Dispatchers.IO) { repository.sendSos(body) }
                                    record("SOS stored: $id; location ${if (location == null) "unavailable/omitted" else "included"}")
                                    notifyUser("SOS queued${if (location == null) " without location" else " with recent location"}. Delivery is not guaranteed.")
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    record("SOS failed: ${error.message}")
                                    notifyUser("SOS was not confirmed stored. Check Debug.")
                                } finally {
                                    sendingSos = false
                                }
                            }
                        }
                    },
                )
                AppTab.DEBUG -> DebugScreen(
                    logs = log.toList(),
                    connectedCount = connected,
                    securityLabel = securityLabel,
                    storageLabel = storageLabel,
                    radioLog = radioEntries.map { entry ->
                        "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timeMillis))}  ${entry.message}"
                    },
                    onClear = { log.clear() },
                    onCopy = {
                        val radioText = radioEntries.joinToString("\n") { "${it.timeMillis}  ${it.message}" }
                        clipboard.setText(AnnotatedString("APP EVENTS\n${log.joinToString("\n")}\n\nRADIO\n$radioText"))
                        notifyUser("Debug log copied. Message bodies and private keys are not logged here.")
                    },
                )
            }
        }
    }

    inspectedContact?.let { card ->
        var compared by remember(card) { mutableStateOf(false) }
        var saving by remember(card) { mutableStateOf(false) }
        val fingerprint = remember(card, services) {
            runCatching { services?.security?.fingerprint(card) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { if (!saving) inspectedContact = null },
            title = { Text("Verify ${card.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Device ID: ${card.deviceId}")
                    Text(fingerprint ?: "Fingerprint unavailable. Security must be connected first.")
                    Text("Compare this contact fingerprint with the other person's own phone through a trusted channel. A matching display name is not proof of identity.")
                    if (!card.verified) Row {
                        Checkbox(checked = compared, onCheckedChange = { compared = it })
                        Text("I compared the fingerprints and they match.", Modifier.padding(top = 8.dp))
                    } else Text("This contact is already marked verified.")
                }
            },
            confirmButton = {
                if (card.verified) TextButton(onClick = { inspectedContact = null }) { Text("Done") }
                else Button(
                    enabled = compared && !fingerprint.isNullOrBlank() && !saving && services?.repository != null,
                    onClick = {
                        val active = services
                        val repository = active?.repository
                        if (repository != null) {
                            saving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { repository.saveContact(card.copy(verified = true)) }
                                    record("Contact verified by user: ${card.deviceId}")
                                    inspectedContact = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    notifyUser("Could not save contact verification: ${error.message}")
                                } finally { saving = false }
                            }
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Mark verified") }
            },
            dismissButton = {
                if (!saving) TextButton(onClick = { inspectedContact = null }) { Text("Close") }
            },
        )
    }
}
