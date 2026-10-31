package com.slackers.emergencymesh.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.slackers.emergencymesh.MainActivity
import com.slackers.emergencymesh.core.DiscoveryMode
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.PeerState
import com.slackers.emergencymesh.core.Priority
import com.slackers.emergencymesh.transport.LocalDevice
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.SosScanSessionStore
import com.slackers.emergencymesh.ui.hasPermission
import com.slackers.emergencymesh.ui.missingNearbyPermissions
import com.slackers.emergencymesh.ui.recentLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the process and CPU awake while the user has the mesh on, including with the screen off.
 * [MeshSession] stays in charge: this service follows it and shows the user that the mesh is on.
 */
class MeshForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    private val ringer by lazy { Ringer(scope, DeviceRingOutput(this)) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mesh status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val services = MeshRuntime.get(this)
        val session = services.session
        if (intent?.action == ACTION_STOP) {
            session.stop()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_RINGING) {
            ringer.stop()
            if (!session.running.value) stopSelfResult(startId)
            return START_STICKY
        }
        if (!enterForeground(restarted = intent == null)) {
            session.keepAliveChanged(false)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        session.keepAliveChanged(true)
        holdCpu()
        // A null intent means Android restarted this service after killing the process.
        if (intent == null) restore(services)
        followSession(services)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        MeshRuntime.get(this).session.keepAliveChanged(false)
        super.onDestroy()
    }

    /** The radio must keep running even if Android refuses the microphone part of this. */
    private fun enterForeground(restarted: Boolean): Boolean {
        val radio = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return (mayUseMicrophone(restarted) && startAs(radio or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) ||
            startAs(radio)
    }

    /** Android only grants the microphone from the screen, so a restart can't ask for it. */
    private fun mayUseMicrophone(restarted: Boolean): Boolean =
        !restarted && Build.VERSION.SDK_INT >= 30 &&
            MeshRuntime.get(this).talkBack?.allowed?.value == true &&
            hasPermission(this, Manifest.permission.RECORD_AUDIO)

    /** Android can refuse a restart from the background, unless battery optimization is off. */
    private fun startAs(types: Int): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(meshStatusText(connected = 0, waiting = 0, problem = null)),
            types,
        )
        true
    } catch (refused: RuntimeException) {
        false
    }

    // The discovery timers stop when the CPU sleeps. Process death also releases this lock.
    @SuppressLint("WakelockTimeout")
    private fun holdCpu() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun restore(services: AppServices) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                check(services.initializationError == null) { "Identity unavailable" }
                check(missingNearbyPermissions(this@MeshForegroundService).isEmpty()) { "Permissions missing" }
                val card = services.security?.localContactCard()
                val device = card?.let { LocalDevice(it.deviceId, it.displayName) } ?: services.device
                val sosActive = SosScanSessionStore(this@MeshForegroundService)
                    .readUntil() > System.currentTimeMillis()
                val mode = if (sosActive) DiscoveryMode.CONTINUOUS else DiscoveryMode.BALANCED
                services.session.start(device, mode, serviceRunning = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                services.session.stop()
            }
        }
    }

    /** Using the latest start ID means a stop can never cancel a start that is on its way. */
    private fun followSession(services: AppServices) {
        if (following?.isActive == true) return
        val session = services.session
        val notifications = getSystemService(NotificationManager::class.java)
        following = scope.launch {
            launch { alertNewMessages(services) }
            launch { ringWhenCalled(services) }
            launch { watchBattery(services) }
            launch { listenForNoAppSos(services) }
            launch { answerQuestions(services) }
            combine(session.running, services.transport.peers, session.problem) { running, peers, problem ->
                if (running) meshStatusText(
                    connected = peers.count { it.state == PeerState.CONNECTED },
                    waiting = peers.count { it.state == PeerState.AWAITING_APPROVAL },
                    problem = problem,
                ) else null
            }.distinctUntilChanged().collect { status ->
                if (status == null) stopSelfResult(latestStartId)
                else notifications.notify(NOTIFICATION_ID, notification(status))
            }
        }
    }

    private suspend fun alertNewMessages(services: AppServices) {
        val repository = services.repository ?: return
        val alerts = MeshAlerts(this)
        val newMessages = NewMessages()
        // An alert is a courtesy. A storage failure must never take the relay down with it.
        repository.recentIncoming.catch { }.collect { messages ->
            val arrived = newMessages.pick(messages)
            if (arrived.isNotEmpty()) {
                val contacts = repository.contacts.catch { emit(emptyList()) }.first()
                arrived.forEach { message ->
                    alerts.show(message, contacts.firstOrNull { it.deviceId == message.senderId }?.displayName)
                }
            }
        }
    }

    private suspend fun ringWhenCalled(services: AppServices) {
        val repository = services.repository ?: return
        val alerts = MeshAlerts(this)
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP_RINGING),
            PendingIntent.FLAG_IMMUTABLE,
        )
        repository.incomingCallOuts.collect { callerId ->
            ringer.ring()
            val contacts = repository.contacts.catch { emit(emptyList()) }.first()
            alerts.showCallOut(contacts.firstOrNull { it.deviceId == callerId }?.displayName, stop)
        }
    }

    private suspend fun watchBattery(services: AppServices) {
        val repository = services.repository ?: return
        val sosSession = SosScanSessionStore(this)
        val lastBreath = LastBreath(
            sosUntil = sosSession::readUntil,
            sentFor = sosSession::lastBreathSentFor,
            markSent = sosSession::markLastBreathSent,
            alerts = { repository.sosAlerts.first() },
            send = { repository.sendSos(it) },
        )
        batteryStates().distinctUntilChanged().collect { state ->
            // A storage failure here must not take the relay down. The next change tries again.
            runCatching { lastBreath.onBattery(state) }
        }
    }

    private fun batteryStates(): Flow<BatteryState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    trySend(BatteryState(level * 100 / scale, plugged))
                }
            }
        }
        ContextCompat.registerReceiver(
            this@MeshForegroundService,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { unregisterReceiver(receiver) }
    }

    private suspend fun listenForNoAppSos(services: AppServices) {
        val repository = services.repository ?: return
        val control = services.noApp ?: return
        val detector = NoAppSosDetector()
        control.listening.collectLatest { listening ->
            if (!listening) return@collectLatest
            coroutineScope {
                launch {
                    bluetoothNames().collect { heard ->
                        val text = detector.heard(heard.address, heard.name) ?: return@collect
                        // A storage failure here must not take the relay down.
                        runCatching {
                            val location = withContext(Dispatchers.IO) { recentLocation(this@MeshForegroundService) }
                            repository.sendSos(
                                MessageBody(
                                    text = text,
                                    latitude = location?.latitude,
                                    longitude = location?.longitude,
                                    accuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy,
                                    locationTimeMillis = location?.time,
                                ),
                            )
                            control.alertSent()
                        }
                    }
                }
                while (isActive) {
                    control.scanOnce()
                    delay(NO_APP_SCAN_MILLIS)
                }
            }
        }
    }

    private fun bluetoothNames(): Flow<HeardName> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(
                    intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                ) ?: return
                trySend(HeardName(device.address, intent.getStringExtra(BluetoothDevice.EXTRA_NAME)))
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            // A name can arrive after the device does, in a separate update.
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        // Exported because these come from the Bluetooth app, and only it may send these actions.
        ContextCompat.registerReceiver(
            this@MeshForegroundService, receiver, filter, ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { unregisterReceiver(receiver) }
    }

    private suspend fun answerQuestions(services: AppServices) {
        val repository = services.repository ?: return
        val control = services.talkBack ?: return
        val talkBack = TalkBack(
            voice = DeviceVoice(this),
            mayListen = {
                control.allowed.value && hasPermission(this, Manifest.permission.RECORD_AUDIO)
            },
            reply = { rescuer, text -> repository.sendDirect(rescuer, text, Priority.P0_SOS) },
        )
        repository.incomingQuestions.collect { question ->
            // A speech failure here must not take the relay down.
            runCatching { talkBack.ask(question) }
        }
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("EmergencyMesh is running")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.slackers.emergencymesh.action.STOP_MESH"
        private const val ACTION_STOP_RINGING = "com.slackers.emergencymesh.action.STOP_RINGING"
        private const val CHANNEL_ID = "mesh_status"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "EmergencyMesh:relay"

        /** False when Android refuses, which it does unless the app is on screen. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, MeshForegroundService::class.java))
            true
        } catch (refused: IllegalStateException) {
            false
        }
    }
}
