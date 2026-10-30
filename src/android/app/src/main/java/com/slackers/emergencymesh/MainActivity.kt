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
            // Add M3's real repository and M4's real security here when available.
            // No send-capable fallback is installed while these are absent.
        )
    }
}
