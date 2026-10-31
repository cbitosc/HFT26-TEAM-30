package com.slackers.emergencymesh.background

import android.content.Context
import com.slackers.emergencymesh.core.ContactCardService
import com.slackers.emergencymesh.data.EmergencyMeshDatabase
import com.slackers.emergencymesh.data.RoomContactBook
import com.slackers.emergencymesh.engine.MessageRepositoryImpl
import com.slackers.emergencymesh.security.SecurityProvider
import com.slackers.emergencymesh.transport.LocalDeviceStore
import com.slackers.emergencymesh.transport.NearbyMeshTransport
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.EngineLifecycle
import com.slackers.emergencymesh.ui.MessageStatusTimes
import com.slackers.emergencymesh.ui.StoredMessages
import com.slackers.emergencymesh.ui.isDebugBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

/** One set of services per process, so reopening the screen finds the mesh still running. */
internal object MeshRuntime {
    @Volatile
    private var services: AppServices? = null

    fun get(context: Context): AppServices = services ?: synchronized(this) {
        services ?: create(context.applicationContext).also { services = it }
    }

    private fun create(context: Context): AppServices {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dashboardLink = DashboardLinkStore(context)
        val transport = NearbyMeshTransport.create(context, LaptopNode(dashboardLink.link))
        // A persisted debug topology must never silently restrict a release build.
        if (!isDebugBuild(context)) transport.setDebugAllowlist(null)
        val database = EmergencyMeshDatabase.getInstance(context)
        val dao = database.emergencyMeshDao()
        val securityResult = runCatching { SecurityProvider.create(context) }
        val security = securityResult.getOrNull()
        val repository = security?.let { MessageRepositoryImpl(dao, transport, it, scope) }
        val contactCards = security?.let { ContactCardService(transport, it, RoomContactBook(dao)) }
        contactCards?.start(scope)
        val engineLifecycle = repository?.let { engine ->
            EngineLifecycle(
                startAndAwaitReady = { engine.startAndAwaitReady() },
                stop = { engine.stop() },
            )
        }
        return AppServices(
            transport = transport,
            device = LocalDeviceStore.load(context),
            session = MeshSession(
                transport = transport,
                engine = engineLifecycle,
                startKeepAlive = { MeshForegroundService.start(context) },
                scope = scope,
            ),
            dashboard = repository?.let { DashboardConnection.start(dashboardLink, it, scope) },
            security = security,
            repository = repository,
            engineLifecycle = engineLifecycle,
            initializationError = securityResult.exceptionOrNull()?.let {
                "The device identity could not be initialized. Check this build and retry."
            },
            storedMessages = StoredMessages(dao),
            radioLog = transport.log,
            readDebugAllowlist = { transport.debugAllowlist },
            readDiscoveryMode = { transport.discoveryMode },
            keyWarnings = contactCards?.keyWarnings,
            statusTimes = repository?.let { engine ->
                { messageId: String ->
                    engine.observeStatusTimes(messageId).map { times ->
                        times?.let {
                            MessageStatusTimes(it.sentAtMillis, it.relayedAtMillis, it.deliveredAtMillis)
                        }
                    }
                }
            },
        )
    }
}
