package com.slackers.emergencymesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.slackers.emergencymesh.data.EmergencyMeshDatabase
import com.slackers.emergencymesh.security.SecurityProvider
import com.slackers.emergencymesh.transport.LocalDeviceStore
import com.slackers.emergencymesh.transport.NearbyMeshTransport
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.EmergencyMeshApp
import com.slackers.emergencymesh.ui.EmergencyMeshTheme
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
        // No plaintext fallback if the real Keystore-backed identity cannot be opened.
        val securityResult = runCatching { SecurityProvider.create(applicationContext) }
        return AppServices(
            transport = transport,
            device = LocalDeviceStore.load(applicationContext),
            security = securityResult.getOrNull(),
            initializationError = securityResult.exceptionOrNull()?.let {
                "Secure identity could not be opened. Check the device Keystore and retry."
            },
            storedMessages = StoredMessages(database.emergencyMeshDao()),
            radioLog = transport.log,
            readDebugAllowlist = { transport.debugAllowlist },
            // Waiting on the published M3 engine/repository constructor.
            // Attach those real instances here, with EngineLifecycle wired to M3's
            // actual start/readiness/stop API. The UI awaits it before transport.start.
            // M4's provider is attached; secure cross-phone messaging still needs engine tests.
        )
    }
}
