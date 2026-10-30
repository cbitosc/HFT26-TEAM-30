package com.slackers.emergencymesh.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Reopening the same on-disk Room database models closing and restarting the app process. */
@RunWith(AndroidJUnit4::class)
class RoomRestartPersistenceTest {
    @Test
    fun pendingMessageAndPacketSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "restart-proof-${UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        try {
            val firstDatabase = Room.databaseBuilder(
                context,
                EmergencyMeshDatabase::class.java,
                databaseName,
            ).allowMainThreadQueries().build()
            try {
                firstDatabase.emergencyMeshDao().saveOutgoing(
                    message = MessageEntity(
                        messageId = id,
                        conversationDeviceId = "recipient-device",
                        senderId = "local-device",
                        outgoing = true,
                        type = "MESSAGE",
                        priority = 2,
                        body = byteArrayOf(1, 2, 3),
                        createdAtMillis = now,
                        status = "PENDING",
                        hopCount = 0,
                    ),
                    packet = QueuedPacketEntity(
                        packetId = id,
                        messageId = id,
                        recipientDeviceId = "recipient-device",
                        encodedPacket = byteArrayOf(4, 5, 6),
                        firstSeenAtMillis = now,
                        queuedAtMillis = now,
                        nextAttemptAtMillis = now,
                        attemptCount = 0,
                        outgoing = true,
                    ),
                )
            } finally {
                firstDatabase.close()
            }

            val restartedDatabase = Room.databaseBuilder(
                context,
                EmergencyMeshDatabase::class.java,
                databaseName,
            ).allowMainThreadQueries().build()
            try {
                val dao = restartedDatabase.emergencyMeshDao()
                val restored = dao.message(id)
                assertNotNull("Pending message should remain after reopening Room", restored)
                assertEquals("PENDING", restored?.status)
                assertNotNull("Its queued packet should remain after reopening Room", dao.queuedPacket(id))
            } finally {
                restartedDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
