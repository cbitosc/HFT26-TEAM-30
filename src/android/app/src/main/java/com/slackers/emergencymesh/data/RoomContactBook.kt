package com.slackers.emergencymesh.data

import com.slackers.emergencymesh.core.ContactBook
import com.slackers.emergencymesh.core.ContactCard

class RoomContactBook(private val dao: EmergencyMeshDao) : ContactBook {
    override suspend fun find(deviceId: String): ContactCard? =
        dao.contact(deviceId)?.let {
            ContactCard(
                deviceId = it.deviceId,
                displayName = it.displayName,
                signingPublicKey = it.signingPublicKey,
                encryptionPublicKey = it.encryptionPublicKey,
                verified = it.verified,
            )
        }

    override suspend fun save(card: ContactCard) {
        dao.putContact(
            ContactEntity(
                deviceId = card.deviceId,
                displayName = card.displayName,
                signingPublicKey = card.signingPublicKey,
                encryptionPublicKey = card.encryptionPublicKey,
                verified = card.verified,
            ),
        )
    }
}
