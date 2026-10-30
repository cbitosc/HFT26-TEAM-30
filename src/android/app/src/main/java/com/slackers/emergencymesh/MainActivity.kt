package com.slackers.emergencymesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.slackers.emergencymesh.data.EmergencyMeshDatabase
import com.slackers.emergencymesh.engine.MessageRepositoryImpl
import com.slackers.emergencymesh.security.SecurityProvider
import com.slackers.emergencymesh.transport.LocalDeviceStore
import com.slackers.emergencymesh.transport.NearbyMeshTransport
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.EmergencyMeshApp
import com.slackers.emergencymesh.ui.EmergencyMeshTheme
import com.slackers.emergencymesh.ui.EngineEventGate
import com.slackers.emergencymesh.ui.EngineLifecycle
import com.slackers.emergencymesh.ui.StoredMessages
import com.slackers.emergencymesh.ui.isDebugBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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
        val engineTransport = EngineEventGate(transport)
        val engine = security?.let {
            MessageRepositoryImpl(
                dao = database.emergencyMeshDao(),
                transport = engineTransport,
                security = it,
                scope = CoroutineScope(lifecycleScope.coroutineContext + Dispatchers.Main.immediate),
            )
        }
        return AppServices(
            transport = transport,
            device = LocalDeviceStore.load(applicationContext),
            security = security,
            repository = engine,
            engineLifecycle = engine?.let { repository ->
                EngineLifecycle(
                    startAndAwaitReady = {
                        engineTransport.startAndAwaitSubscription(repository::start)
                    },
                    stop = repository::stop,
                )
            },
            initializationError = securityResult.exceptionOrNull()?.let {
                "The device identity could not be initialized. Check this build and retry."
            },
            storedMessages = StoredMessages(database.emergencyMeshDao()),
            radioLog = transport.log,
            readDebugAllowlist = { transport.debugAllowlist },
            // Restore M4's Tink provider only after relay passes. Never call this debug build secure.
        )
    }
}
