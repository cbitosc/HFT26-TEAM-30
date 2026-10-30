package com.slackers.emergencymesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.slackers.emergencymesh.core.ContactCardService
import com.slackers.emergencymesh.data.EmergencyMeshDatabase
import com.slackers.emergencymesh.data.RoomContactBook
import com.slackers.emergencymesh.engine.MessageRepositoryImpl
import com.slackers.emergencymesh.security.SecurityProvider
import com.slackers.emergencymesh.transport.LocalDeviceStore
import com.slackers.emergencymesh.transport.NearbyMeshTransport
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.EmergencyMeshApp
import com.slackers.emergencymesh.ui.EmergencyMeshTheme
import com.slackers.emergencymesh.ui.EngineLifecycle
import com.slackers.emergencymesh.ui.StoredMessages
import com.slackers.emergencymesh.ui.isDebugBuild

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = createAppServices()
        setContent {
            EmergencyMeshTheme {
                EmergencyMeshApp(services = services)
            }
        }
    }

    private fun createAppServices(): AppServices {
        val transport = NearbyMeshTransport.create(applicationContext)
        // A persisted debug topology must never silently restrict a release build.
        if (!isDebugBuild(applicationContext)) transport.setDebugAllowlist(null)
        val database = EmergencyMeshDatabase.getInstance(applicationContext)
        val dao = database.emergencyMeshDao()
        val securityResult = runCatching { SecurityProvider.create(applicationContext) }
        val security = securityResult.getOrNull()
        val repository = security?.let { MessageRepositoryImpl(dao, transport, it, lifecycleScope) }
        security?.let { ContactCardService(transport, it, RoomContactBook(dao)).start(lifecycleScope) }
        return AppServices(
            transport = transport,
            device = LocalDeviceStore.load(applicationContext),
            security = security,
            repository = repository,
            engineLifecycle = repository?.let { engine ->
                EngineLifecycle(
                    startAndAwaitReady = { engine.startAndAwaitReady() },
                    stop = { engine.stop() },
                )
            },
            initializationError = securityResult.exceptionOrNull()?.let {
                "The device identity could not be initialized. Check this build and retry."
            },
            storedMessages = StoredMessages(dao),
            radioLog = transport.log,
            readDebugAllowlist = { transport.debugAllowlist },
        )
    }
}
