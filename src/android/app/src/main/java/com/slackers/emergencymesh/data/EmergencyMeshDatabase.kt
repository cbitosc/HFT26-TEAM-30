package com.slackers.emergencymesh.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

/** The stable database name is intentional: it keeps the local queue across process restarts. */
@Database(
    entities = [
        ContactEntity::class,
        MessageEntity::class,
        QueuedPacketEntity::class,
        SeenPacketEntity::class,
        PeerReceiptEntity::class,
    ],
    version = 1,
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
                ).build().also { instance = it }
            }
    }
}
