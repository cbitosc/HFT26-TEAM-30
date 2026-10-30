package com.slackers.emergencymesh.data

import android.test.AndroidTestCase
import androidx.room.Room
import java.util.UUID

/** Reopening the same on-disk Room database models closing and restarting the app process. */
class RoomRestartPersistenceTest : AndroidTestCase() {
    fun testPendingMessageAndPacketSurviveDatabaseReopen() {
        val databaseName = "restart-proof-${UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        try {
            Room.databaseBuilder(context, EmergencyMeshDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build().use { database ->
                    database.emergencyMeshDao().saveOutgoing(
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
                }

            Room.databaseBuilder(context, EmergencyMeshDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build().use { restartedDatabase ->
                    val dao = restartedDatabase.emergencyMeshDao()
                    val restored = dao.message(id)
                    assertNotNull("Pending message should remain after reopening Room", restored)
                    assertEquals("PENDING", restored?.status)
                    assertNotNull("Its queued packet should remain after reopening Room", dao.queuedPacket(id))
                }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
