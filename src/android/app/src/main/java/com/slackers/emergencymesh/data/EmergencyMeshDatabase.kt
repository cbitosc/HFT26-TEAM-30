package com.slackers.emergencymesh.data

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase

/** The stable database name is intentional: it keeps the local queue across process restarts. */
@Database(
    entities = [
        ContactEntity::class,
        MessageEntity::class,
        QueuedPacketEntity::class,
        SeenPacketEntity::class,
        PeerReceiptEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class EmergencyMeshDatabase : RoomDatabase() {
    abstract fun emergencyMeshDao(): EmergencyMeshDao

    companion object {
        const val DATABASE_NAME = "emergency_mesh.db"

        @Volatile
        private var instance: EmergencyMeshDatabase? = null

        fun getInstance(context: Context): EmergencyMeshDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EmergencyMeshDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN sentAtMillis INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN relayedAtMillis INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveredAtMillis INTEGER")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queued_packets ADD COLUMN priority INTEGER NOT NULL DEFAULT 2")
                db.execSQL(
                    "UPDATE queued_packets SET priority = " +
                        "COALESCE((SELECT priority FROM messages " +
                        "WHERE messages.messageId = queued_packets.messageId), 2)",
                )
                db.execSQL("ALTER TABLE queued_packets ADD COLUMN receivedFromDeviceId TEXT")
                db.execSQL("ALTER TABLE queued_packets ADD COLUMN expiresAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE queued_packets SET expiresAtMillis = firstSeenAtMillis + " +
                        "CASE priority WHEN 0 THEN 86400000 WHEN 1 THEN 43200000 ELSE 21600000 END",
                )

                // Receipts now belong to a packet UUID, not its parent message UUID.
                db.execSQL(
                    "CREATE TABLE peer_receipts_v2 (" +
                        "packetId TEXT NOT NULL, " +
                        "peerDeviceId TEXT NOT NULL, " +
                        "acknowledgedAtMillis INTEGER, " +
                        "attemptCount INTEGER NOT NULL, " +
                        "nextAttemptAtMillis INTEGER NOT NULL, " +
                        "PRIMARY KEY(packetId, peerDeviceId))",
                )
                db.execSQL(
                    "INSERT INTO peer_receipts_v2 " +
                        "(packetId, peerDeviceId, acknowledgedAtMillis, attemptCount, nextAttemptAtMillis) " +
                        "SELECT messageId, peerDeviceId, acknowledgedAtMillis, attemptCount, nextAttemptAtMillis " +
                        "FROM peer_receipts",
                )
                db.execSQL("DROP TABLE peer_receipts")
                db.execSQL("ALTER TABLE peer_receipts_v2 RENAME TO peer_receipts")
                db.execSQL("CREATE INDEX index_peer_receipts_peerDeviceId ON peer_receipts(peerDeviceId)")
            }
        }
    }
}
