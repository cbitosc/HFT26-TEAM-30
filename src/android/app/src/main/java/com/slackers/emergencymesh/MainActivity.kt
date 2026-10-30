package com.slackers.emergencymesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.slackers.emergencymesh.data.EmergencyMeshDatabase
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
import androidx.lifecycle.lifecycleScope

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
        // Relay-first phase: debug explicitly selects M1's labelled plaintext provider.
        // Release selects no provider and cannot send until Tink is re-enabled.
        val securityResult = runCatching { SecurityProvider.create(applicationContext) }
        val security = securityResult.getOrNull()
        val repository = security?.let {
            MessageRepositoryImpl(database.emergencyMeshDao(), transport, it, lifecycleScope)
        }
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
            storedMessages = StoredMessages(database.emergencyMeshDao()),
            radioLog = transport.log,
            readDebugAllowlist = { transport.debugAllowlist },
            // startAndAwaitReady subscribes before EmergencyMeshApp starts the radio.
            // This debug provider remains explicitly labelled plaintext until M4's Tink provider
            // is integrated; do not treat the debug build as secure.
        )
    }
}
