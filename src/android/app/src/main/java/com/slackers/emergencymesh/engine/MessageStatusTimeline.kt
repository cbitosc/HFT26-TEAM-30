package com.slackers.emergencymesh.engine

import kotlinx.coroutines.flow.Flow

/** First local observation time for each acknowledgement-backed status milestone. */
data class MessageStatusTimes(
    val sentAtMillis: Long?,
    val relayedAtMillis: Long?,
    val deliveredAtMillis: Long?,
)

/**
 * M3-owned status-time API for M2 to observe alongside the shared ChatMessage contract.
 * MessageRepositoryImpl implements this as well as MessageRepository, so UI code can cast the
 * repository without changing M1-owned core contracts.
 */
interface MessageStatusTimeline {
    fun observeStatusTimes(messageId: String): Flow<MessageStatusTimes?>
}
