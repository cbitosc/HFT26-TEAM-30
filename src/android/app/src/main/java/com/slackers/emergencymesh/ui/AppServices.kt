package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.ContactKeyWarning
import com.slackers.emergencymesh.core.MessageRepository
import com.slackers.emergencymesh.core.MessageSecurity
import com.slackers.emergencymesh.transport.LocalDevice
import com.slackers.emergencymesh.transport.TransportLogEntry
import kotlinx.coroutines.flow.StateFlow

/** M2's wiring boundary. No mock, plaintext, or in-memory fallback is installed here. */
data class AppServices(
    val transport: MeshTransport,
    val device: LocalDevice,
    val repository: MessageRepository? = null,
    val security: MessageSecurity? = null,
    val engineLifecycle: EngineLifecycle? = null,
    /** Read-only bridge while M3's repository/engine is still being implemented. */
    val storedMessages: StoredMessages? = null,
    val radioLog: StateFlow<List<TransportLogEntry>>? = null,
    val readDebugAllowlist: () -> Set<String>? = { null },
    val initializationError: String? = null,
    val keyWarnings: StateFlow<List<ContactKeyWarning>>? = null,
)
