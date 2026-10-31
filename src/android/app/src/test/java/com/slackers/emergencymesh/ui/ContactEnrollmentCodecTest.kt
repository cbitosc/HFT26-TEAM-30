package com.slackers.emergencymesh.ui

import com.slackers.emergencymesh.core.ContactCard
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactEnrollmentCodecTest {
    private val card = ContactCard("device-A", "Phone A", byteArrayOf(1, 2), byteArrayOf(3, 4), true)

    @Test
    fun transferringPublicKeysCannotTransferSomeoneElsesVerifiedFlag() {
        val decoded = ContactEnrollmentCodec.decode(ContactEnrollmentCodec.encode(card))
        assertEquals(card.deviceId, decoded.deviceId)
        assertArrayEquals(card.signingPublicKey, decoded.signingPublicKey)
        assertArrayEquals(card.encryptionPublicKey, decoded.encryptionPublicKey)
        assertFalse(decoded.verified)
    }

    @Test
    fun importedKeysCannotOverwriteAlreadyPinnedKeys() {
        val impostor = card.copy(encryptionPublicKey = byteArrayOf(9))
        assertThrows(IllegalArgumentException::class.java) {
            contactForEnrollment(impostor, card, "local-device", true)
        }
    }

    @Test
    fun reimportDoesNotDowngradeExistingTrustOrRenameVerifiedContact() {
        val remoteRename = card.copy(displayName = "Untrusted rename", verified = false)
        assertEquals(card, contactForEnrollment(remoteRename, card, "local-device", false))
    }

    @Test
    fun manualComparisonIsRequiredForANewContact() {
        assertFalse(contactForEnrollment(card, null, "local-device", false).verified)
        assertTrue(contactForEnrollment(card, null, "local-device", true).verified)
    }

    @Test
    fun selfCardAndInvalidOrOversizedTextAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { contactForEnrollment(card, null, card.deviceId, true) }
        assertThrows(IllegalArgumentException::class.java) { ContactEnrollmentCodec.decode("not a card") }
        assertThrows(IllegalArgumentException::class.java) { ContactEnrollmentCodec.decode("x".repeat(33_000)) }
        assertThrows(IllegalArgumentException::class.java) { ContactEnrollmentCodec.decode("EMESH-CONTACT-1:AAAA") }
    }
}
