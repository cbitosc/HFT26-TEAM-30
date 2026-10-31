package com.slackers.emergencymesh.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RingerTest {
    private class RecordingOutput : RingOutput {
        val events = mutableListOf<String>()
        val beats get() = events.count { it.startsWith("beat") }

        override fun begin() {
            events += "begin"
        }

        override fun beat(on: Boolean) {
            events += "beat $on"
        }

        override fun end() {
            events += "end"
        }
    }

    private val output = RecordingOutput()

    private fun TestScope.ringer() = Ringer(this, output, ringMillis = 1_000, flashMillis = 250)

    @Test
    fun aRingFlashesForItsFixedTimeThenStopsByItself() = runTest {
        ringer().ring()
        advanceUntilIdle()
        assertEquals(
            listOf("begin", "beat true", "beat false", "beat true", "beat false", "end"),
            output.events,
        )
    }

    @Test
    fun aCallDuringARingDoesNotExtendIt() = runTest {
        val ringer = ringer()
        ringer.ring()
        advanceTimeBy(500)
        ringer.ring()
        advanceUntilIdle()
        assertEquals(1, output.events.count { it == "begin" })
        assertEquals(4, output.beats)
    }

    @Test
    fun stoppingEndsTheRingAtOnce() = runTest {
        val ringer = ringer()
        ringer.ring()
        advanceTimeBy(300)
        ringer.stop()
        runCurrent()
        assertEquals("end", output.events.last())
        assertEquals(2, output.beats)
        advanceUntilIdle()
        assertEquals(1, output.events.count { it == "end" })
    }

    @Test
    fun aPhoneCanRingAgainAfterARingEnds() = runTest {
        val ringer = ringer()
        ringer.ring()
        advanceUntilIdle()
        ringer.ring()
        advanceUntilIdle()
        assertEquals(2, output.events.count { it == "begin" })
        assertEquals(2, output.events.count { it == "end" })
    }

    @Test
    fun stoppingWithNothingRingingIsSafe() = runTest {
        ringer().stop()
        assertTrue(output.events.isEmpty())
    }

    @Test
    fun theServiceEndingTurnsEverythingOff() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        Ringer(scope, output, ringMillis = 1_000, flashMillis = 250).ring()
        advanceTimeBy(300)
        scope.cancel()
        advanceUntilIdle()
        assertEquals("end", output.events.last())
    }
}
