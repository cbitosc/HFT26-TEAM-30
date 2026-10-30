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
        // M1 supplies a clearly labelled debug provider; release returns null until M4 lands.
        val security = SecurityProvider.create(applicationContext)
        return AppServices(
            transport = transport,
            device = LocalDeviceStore.load(applicationContext),
            security = security,
            storedMessages = StoredMessages(database.emergencyMeshDao()),
            radioLog = transport.log,
            readDebugAllowlist = { transport.debugAllowlist },
            // Waiting on the published M3 engine/repository constructor.
            // Attach those real instances here, with EngineLifecycle wired to M3's
            // actual start/readiness/stop API. The UI awaits it before transport.start.
            // SecurityProvider must be replaced by M4 before any secure-build claim.
        )
    }
}
