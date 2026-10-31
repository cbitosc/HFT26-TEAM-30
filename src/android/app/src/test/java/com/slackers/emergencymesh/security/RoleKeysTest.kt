package com.slackers.emergencymesh.security

import com.slackers.emergencymesh.core.AreaAlert
import com.slackers.emergencymesh.core.MessageBody
import com.slackers.emergencymesh.core.compassDirection
import com.slackers.emergencymesh.core.distanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleKeysTest {
    private val authority = RoleAuthority(
        mapOf(
            AppRole.RESPONDER to rolePublicKey(AppRole.RESPONDER, "responder test code"),
            AppRole.ADMIN to rolePublicKey(AppRole.ADMIN, "admin test code"),
        ),
    )
    private val message = "Flood warning".toByteArray()

    @Test
    fun theRightCodeUnlocksItsRole() {
        assertNotNull(authority.unlock(AppRole.RESPONDER, "responder test code"))
        assertNotNull(authority.unlock(AppRole.ADMIN, " admin test code "))
    }

    @Test
    fun aWrongCodeIsRefused() {
        assertNull(authority.unlock(AppRole.RESPONDER, "guess"))
        assertNull(authority.unlock(AppRole.RESPONDER, "admin test code"))
        assertNull(authority.unlock(AppRole.ADMIN, "responder test code"))
    }

    @Test
    fun aUserHasNoRoleKey() {
        assertNull(authority.unlock(AppRole.USER, "anything"))
    }

    @Test
    fun aRoleSignatureVerifiesOnAnyPhone() {
        val signature = authority.unlock(AppRole.RESPONDER, "responder test code")!!.sign(message)
        assertTrue(authority.verifies(message, signature))
        assertFalse(authority.verifies("Changed".toByteArray(), signature))
        assertFalse(authority.verifies(message, ByteArray(64)))
    }

    @Test
    fun aKeyFromAnotherCodeIsNotTrusted() {
        val outsider = RoleAuthority(mapOf(AppRole.RESPONDER to rolePublicKey(AppRole.RESPONDER, "another code")))
        val signature = outsider.unlock(AppRole.RESPONDER, "another code")!!.sign(message)
        assertFalse(authority.verifies(message, signature))
    }

    @Test
    fun aSessionSignsOnlyWhileLoggedIn() {
        val session = RoleSession(authority)
        assertNull(session.role.value)
        assertNull(session.sign(message))
        assertFalse(session.login(AppRole.ADMIN, "guess"))
        assertNull(session.role.value)
        assertTrue(session.login(AppRole.RESPONDER, "responder test code"))
        assertEquals(AppRole.RESPONDER, session.role.value)
        assertNotNull(session.sign(message))
        session.logOut()
        assertNull(session.sign(message))
        session.continueAsUser()
        assertEquals(AppRole.USER, session.role.value)
        assertNull(session.sign(message))
    }

    @Test
    fun distanceAndDirectionMatchKnownPlaces() {
        val cbit = 17.3917 to 78.3195
        val secunderabad = 17.4399 to 78.4983
        val kilometers = distanceMeters(cbit.first, cbit.second, secunderabad.first, secunderabad.second) / 1_000
        assertTrue("Was $kilometers", kilometers in 19.0..21.0)
        assertEquals("east", compassDirection(cbit.first, cbit.second, cbit.first, secunderabad.second))
        assertEquals("north", compassDirection(cbit.first, cbit.second, 18.0, cbit.second))
        assertEquals("south-west", compassDirection(17.5, 78.5, 17.4, 78.4))
    }

    @Test
    fun anAreaCoversPhonesInsideItsRadiusOnly() {
        val alert = AreaAlert("Flood warning", 17.3917, 78.3195, 1_000f)
        assertTrue(alert.covers(17.3917, 78.3195))
        assertTrue(alert.covers(17.3960, 78.3195))
        assertFalse(alert.covers(17.4399, 78.4983))
    }

    @Test
    fun aMalformedAreaIsRefused() {
        assertNull(AreaAlert.fromBody(MessageBody("No area")))
        assertNull(AreaAlert.fromBody(MessageBody("Bad", 95.0, 78.0, 1_000f)))
        assertNull(AreaAlert.fromBody(MessageBody("Bad", 17.0, 78.0, 0f)))
        assertNull(AreaAlert.fromBody(MessageBody(" ", 17.0, 78.0, 1_000f)))
        assertNotNull(AreaAlert.fromBody(MessageBody("Good", 17.0, 78.0, 1_000f)))
    }
}
