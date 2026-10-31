package com.slackers.emergencymesh.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAppSosTest {
    private var now = 0L
    private val detector = NoAppSosDetector { now }

    @Test
    fun aNameStartingWithSosIsARequestForHelp() {
        assertEquals("3 TRAPPED FLOOR2", noAppSosWords("SOS 3 TRAPPED FLOOR2"))
        assertEquals("3 trapped", noAppSosWords("sos 3 trapped"))
        assertEquals("gate 3", noAppSosWords("  SOS: gate 3 "))
        assertEquals("gate 3", noAppSosWords("SOS-gate 3"))
    }

    @Test
    fun sosAloneStillCounts() {
        assertEquals("", noAppSosWords("SOS"))
        assertEquals("", noAppSosWords("SOS!"))
    }

    @Test
    fun hindiAndTeluguWordsPassThrough() {
        assertEquals("मदद चाहिए", noAppSosWords("SOS मदद चाहिए"))
        assertEquals("సహాయం కావాలి", noAppSosWords("SOS సహాయం కావాలి"))
    }

    @Test
    fun ordinaryNamesAreIgnored() {
        for (name in listOf(null, "", "Galaxy S24", "SOSO Speaker", "My SOS phone", "Sosa's Pixel")) {
            assertNull(name, noAppSosWords(name))
        }
    }

    @Test
    fun hiddenControlCharactersAreRemovedAndLongWordsAreCut() {
        assertEquals("gate3", noAppSosWords("SOS gate\u00003\n"))
        assertEquals(64, noAppSosWords("SOS " + "x".repeat(200))!!.length)
    }

    @Test
    fun everyAlertSaysItIsNotVerified() {
        assertTrue(noAppSosText("3 TRAPPED").startsWith("No-app SOS, not verified."))
        assertTrue(noAppSosText("3 TRAPPED").contains("\"3 TRAPPED\""))
        assertTrue(noAppSosText("").contains("no details"))
    }

    @Test
    fun aScanHearingTheSameNameAgainSendsOneAlert() {
        assertNotNull(detector.heard("AA", "SOS gate 3"))
        now += 60_000
        assertNull(detector.heard("AA", "SOS gate 3"))
    }

    @Test
    fun aPhoneStillCallingForHelpIsRaisedAgainLater() {
        assertNotNull(detector.heard("AA", "SOS gate 3"))
        now += 30 * 60 * 1_000L
        assertNotNull(detector.heard("AA", "SOS gate 3"))
    }

    @Test
    fun newDetailsFromTheSamePhoneAreSentStraightAway() {
        assertNotNull(detector.heard("AA", "SOS gate 3"))
        assertNotNull(detector.heard("AA", "SOS gate 3 water rising"))
    }

    @Test
    fun twoPhonesWithTheSameWordsAreSeparateAlerts() {
        assertNotNull(detector.heard("AA", "SOS gate 3"))
        assertNotNull(detector.heard("BB", "SOS gate 3"))
    }

    @Test
    fun ordinaryNamesNeverUseUpTheLimit() {
        repeat(50) { assertNull(detector.heard("AA$it", "Galaxy $it")) }
        assertNotNull(detector.heard("BB", "SOS gate 3"))
    }

    @Test
    fun aFloodOfFakeNamesIsCappedEachHour() {
        val sent = (1..50).count { detector.heard("AA$it", "SOS fake $it") != null }
        assertEquals(10, sent)
        now += 60 * 60 * 1_000L
        assertNotNull("The limit must clear, or real calls would stay blocked", detector.heard("BB", "SOS real"))
    }
}
