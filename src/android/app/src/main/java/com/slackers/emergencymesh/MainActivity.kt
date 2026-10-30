package com.slackers.emergencymesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.slackers.emergencymesh.ui.AppServices
import com.slackers.emergencymesh.ui.EmergencyMeshApp
import com.slackers.emergencymesh.ui.EmergencyMeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Replace this one factory when M1/M3/M4 supply their real implementations.
        // Null is an explicit integration state, never a fake transport/repository.
        val services = createAppServices()
        setContent {
            EmergencyMeshTheme {
                EmergencyMeshApp(services = services)
            }
        }
    }

    private fun createAppServices(): AppServices? = null
}
