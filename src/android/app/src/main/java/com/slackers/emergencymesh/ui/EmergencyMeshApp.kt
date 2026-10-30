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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    var startingConnection by remember { mutableStateOf(false) }
    var connectionStartJob by remember { mutableStateOf<Job?>(null) }
    var sendingDirect by remember { mutableStateOf(false) }
    var sendingSos by remember { mutableStateOf(false) }
    var directSubmission by remember { mutableStateOf(0) }
    var submittedConversationId by remember { mutableStateOf<String?>(null) }
    var inspectedContact by remember { mutableStateOf<ContactCard?>(null) }
    var enrollmentOpen by remember { mutableStateOf(false) }
    var enrolling by remember { mutableStateOf(false) }
    var enrollmentError by remember { mutableStateOf<String?>(null) }
    var groupCodeOpen by remember { mutableStateOf(false) }
    var savingGroupCode by remember { mutableStateOf(false) }
    var groupCodeError by remember { mutableStateOf<String?>(null) }
    var hasGroupKey by remember(services) { mutableStateOf(services?.security?.hasGroupKey == true) }
    val debugTools = remember(context) { isDebugBuild(context) }
    var topologyOpen by remember { mutableStateOf(false) }
    var topologyError by remember { mutableStateOf<String?>(null) }
    var debugAllowlist by remember(services) { mutableStateOf(services?.readDebugAllowlist?.invoke()) }
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

    fun stopConnection() {
        connectionStartJob?.cancel()
        runCatching { services?.transport?.stop() }
        runCatching { services?.engineLifecycle?.stop?.invoke() }
        discoveryRequested = false
        startingConnection = false
    }

    val localIdentityResult = remember(services) {
        runCatching { services?.security?.localContactCard() }
    }
    val localCard = localIdentityResult.getOrNull()
    val localFingerprint = remember(services, localCard) {
        localCard?.let { card -> runCatching { services?.security?.fingerprint(card) }.getOrNull() }
    }
    val identityError = services?.initializationError ?: localIdentityResult.exceptionOrNull()?.message
    val transportDevice = localCard?.let { LocalDevice(it.deviceId, it.displayName) } ?: device
    val canMessage = services?.repository != null && localCard != null && services.engineLifecycle != null
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
        services.initializationError != null -> "Device identity unavailable"
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
                    stopConnection()
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
                stopConnection()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopConnection()
        }
    }

    fun startDiscovery() {
        if (startingConnection || discoveryRequested || connectionStartJob?.isCompleted == false) return
        val active = services
        val radioDevice = transportDevice
        if (active == null || radioDevice == null) {
            notifyUser("A real transport and device ID must be wired first.")
            return
        }
        if (active.initializationError != null) {
            notifyUser(active.initializationError)
            return
        }
        if (active.security != null && localCard == null) {
            notifyUser("The security identity could not be loaded. Check Debug before starting.")
            return
        }
        if (active.repository != null && active.engineLifecycle == null) {
            notifyUser("Repository attached without its engine lifecycle. Startup is blocked until M3 is wired.")
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
        startingConnection = true
        connectionStartJob = scope.launch {
            try {
                val startRadio = { active.transport.start(radioDevice.deviceId, radioDevice.displayName) }
                val engine = active.engineLifecycle
                if (engine != null) {
                    engine.startBeforeTransport(startRadio)
                    record("Engine subscribed before radio start.")
                } else {
                    startRadio()
                    record("Radio setup only; engine/repository unavailable. Messaging stays disabled.")
                }
                discoveryRequested = true
                permissionFeedback = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching { active.transport.stop() }
                record("Engine/radio start failed: ${error.message}")
                notifyUser("Could not start the connection. Check Debug.")
            } finally {
                startingConnection = false
            }
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
                    else "$storageLabel. Nearby pairing is available. Engine, repository and security must be connected before sending.",
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
                    onEnroll = { enrollmentError = null; enrollmentOpen = true },
                )
                AppTab.NEARBY -> NearbyScreen(
                    peers = peers,
                    localDevice = transportDevice,
                    fingerprint = localFingerprint,
                    discoveryRequested = discoveryRequested,
                    startingConnection = startingConnection,
                    canStart = services != null && transportDevice != null &&
                        services.initializationError == null &&
                        (services.security == null || localCard != null),
                    permissionFeedback = permissionFeedback,
                    radioProblems = radioProblems.map(::radioProblemLabel),
                    discoveryMode = discoveryMode,
                    canRename = services != null && services.security == null && !discoveryRequested && !startingConnection,
                    onRename = { name ->
                        val current = device
                        if (current != null && name.isNotBlank() && services?.security == null && !discoveryRequested && !startingConnection) {
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
                        stopConnection()
                        record("Connection stopped by user.")
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
                    onEnroll = { enrollmentError = null; enrollmentOpen = true },
                )
                AppTab.SOS -> SosScreen(
                    alerts = alerts,
                    sending = sendingSos,
                    canSend = canMessage && hasGroupKey,
                    groupConfigured = hasGroupKey,
                    canConfigureGroup = services?.security?.isDevelopmentPlaintext == false,
                    onConfigureGroup = { groupCodeError = null; groupCodeOpen = true },
                    onRequestLocation = {
                        sosLocationRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION))
                    },
                    onSend = { text, includeLocation ->
                        val active = services
                        val repository = active?.repository
                        if (repository != null && canMessage && hasGroupKey && !sendingSos) {
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
                    showTopologyTools = debugTools && services != null,
                    allowlistSummary = when {
                        debugAllowlist == null -> "Allowlist off · unrestricted radio links"
                        debugAllowlist?.isEmpty() == true -> "All radio peers blocked"
                        else -> "${debugAllowlist?.size} stable device IDs allowed"
                    },
                    onTopology = { topologyError = null; topologyOpen = true },
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

    if (enrollmentOpen) ContactEnrollmentDialog(
        localCard = localCard,
        localDeviceId = transportDevice?.deviceId,
        fingerprint = { card -> runCatching { services?.security?.fingerprint(card) }.getOrNull() },
        canEnroll = services?.repository != null && localCard != null,
        saving = enrolling,
        saveError = enrollmentError,
        groupConfigured = hasGroupKey,
        canConfigureGroup = services?.security?.isDevelopmentPlaintext == false,
        onConfigureGroup = {
            enrollmentOpen = false
            groupCodeError = null
            groupCodeOpen = true
        },
        onCopy = { publicText ->
            clipboard.setText(AnnotatedString(publicText))
            notifyUser("Public contact card copied. Exchange it and compare fingerprints separately.")
        },
        onEnroll = { incoming, compared ->
            val repository = services?.repository
            val ownId = transportDevice?.deviceId
            if (repository != null && ownId != null && localCard != null && !enrolling) {
                enrolling = true
                enrollmentError = null
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            val snapshot = withTimeoutOrNull(5_000) { repository.contacts.first() }
                                ?: throw IllegalStateException("Contact storage did not respond. Try again after it is ready.")
                            val existing = snapshot.firstOrNull { it.deviceId == incoming.deviceId }
                            val card = contactForEnrollment(incoming, existing, ownId, compared)
                            repository.saveContact(card)
                            card
                        }
                        record("Contact enrolled: ${saved.deviceId}; verified=${saved.verified}")
                        enrollmentOpen = false
                        notifyUser(if (saved.verified) "Contact enrolled with fingerprint verification."
                            else "Contact saved as unverified. Compare fingerprints before messaging.")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        enrollmentError = failure.message ?: "Contact could not be saved."
                    } finally { enrolling = false }
                }
            }
        },
        onDismiss = { enrollmentOpen = false },
    )

    if (groupCodeOpen && services?.security?.isDevelopmentPlaintext == false) GroupCodeDialog(
        configured = hasGroupKey,
        saving = savingGroupCode,
        error = groupCodeError,
        onSave = { code ->
            val security = services.security
            if (security != null && !savingGroupCode) {
                savingGroupCode = true
                groupCodeError = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { security.setGroupCode(code) }
                        hasGroupKey = security.hasGroupKey
                        check(hasGroupKey) { "Group setup did not complete." }
                        groupCodeOpen = false
                        record("SOS group configured. The code is not logged.")
                        notifyUser("SOS group configured on this phone. Other phones need the same code.")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        groupCodeError = failure.message ?: "Group code could not be saved."
                    } finally { savingGroupCode = false }
                }
            }
        },
        onDismiss = { groupCodeOpen = false },
    )

    if (topologyOpen && debugTools && services != null) DebugTopologyDialog(
        allowed = debugAllowlist,
        knownDevices = contacts.associate { it.deviceId to it.displayName } +
            peers.associate { it.deviceId to it.displayName },
        localDeviceId = transportDevice?.deviceId,
        applyError = topologyError,
        onApply = { requested ->
            if (debugTools) {
                try {
                    services.transport.setDebugAllowlist(requested)
                    debugAllowlist = services.readDebugAllowlist()
                    record("Debug radio filter applied: ${requested?.joinToString() ?: "unrestricted"}")
                    topologyOpen = false
                } catch (failure: Exception) {
                    topologyError = failure.message ?: "Allowlist could not be changed."
                }
            }
        },
        onDismiss = { topologyOpen = false },
    )

    inspectedContact?.let { card ->
        var compared by remember(card) { mutableStateOf(false) }
        var saving by remember(card) { mutableStateOf(false) }
        var verificationError by remember(card) { mutableStateOf<String?>(null) }
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
                    verificationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (card.verified) TextButton(onClick = { inspectedContact = null }) { Text("Done") }
                else Button(
                    enabled = compared && !fingerprint.isNullOrBlank() && !saving && services?.repository != null,
                    onClick = {
                        val active = services
                        val repository = active?.repository
                        val ownId = transportDevice?.deviceId
                        if (repository != null && ownId != null) {
                            saving = true
                            verificationError = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val latest = withTimeoutOrNull(5_000) { repository.contacts.first() }
                                            ?: throw IllegalStateException("Contact storage did not respond.")
                                        val existing = latest.firstOrNull { it.deviceId == card.deviceId }
                                            ?: throw IllegalStateException("This contact was removed. Enroll it again.")
                                        repository.saveContact(contactForEnrollment(card, existing, ownId, true))
                                    }
                                    record("Contact verified by user: ${card.deviceId}")
                                    inspectedContact = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    verificationError = error.message ?: "Could not save contact verification."
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
