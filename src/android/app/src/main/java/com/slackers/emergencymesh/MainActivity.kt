package com.slackers.emergencymesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.slackers.emergencymesh.data.EmergencyMeshDatabase
import com.slackers.emergencymesh.transport.LocalDeviceStore
import com.slackers.emergencymesh.transport.NearbyMeshTransport
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.EmergencyMeshApp
import com.slackers.emergencymesh.ui.EmergencyMeshTheme
import com.slackers.emergencymesh.ui.StoredMessages

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
        val database = EmergencyMeshDatabase.getInstance(applicationContext)
        return AppServices(
            transport = transport,
            device = LocalDeviceStore.load(applicationContext),
            storedMessages = StoredMessages(database.emergencyMeshDao()),
            radioLog = transport.log,
            // Waiting on the published M3 engine/repository and M4 security constructors.
            // Attach those real instances here, with EngineLifecycle wired to M3's
            // actual start/readiness/stop API. The UI awaits it before transport.start.
            // No placeholder engine or send-capable crypto fallback is installed.
        )
    }
}
