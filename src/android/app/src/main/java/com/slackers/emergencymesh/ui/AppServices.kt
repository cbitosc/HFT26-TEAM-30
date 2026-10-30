package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.MeshTransport
import com.slackers.emergencymesh.core.MessageRepository
import com.slackers.emergencymesh.core.MessageSecurity

/** M2's wiring boundary. No mock, plaintext, or in-memory fallback is installed here. */
data class AppServices(
    val repository: MessageRepository,
    val transport: MeshTransport,
    val security: MessageSecurity,
)
