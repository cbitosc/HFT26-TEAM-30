package com.slackers.emergencymesh.background

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
import com.slackers.emergencymesh.transport.LocalDevice
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.SosScanSessionStore
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
        if (!enterForeground()) {
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

    /** Android can refuse a restart from the background, unless battery optimization is off. */
    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(meshStatusText(connected = 0, waiting = 0, problem = null)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
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
            launch { listenForNoAppSos(services) }
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
