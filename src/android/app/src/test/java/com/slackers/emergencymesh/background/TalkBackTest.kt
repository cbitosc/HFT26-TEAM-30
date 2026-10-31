package com.slackers.emergencymesh.background

import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.SpokenQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TalkBackTest {
    private class FakeVoice(
        private val speaks: Boolean = true,
        private val hears: String? = "my leg is stuck",
    ) : Voice {
        val spoken = mutableListOf<Pair<String, SpokenLanguage>>()
        var listened = 0
        var holdSpeech: CompletableDeferred<Unit>? = null

        override suspend fun speak(text: String, language: SpokenLanguage): Boolean {
            spoken += text to language
            holdSpeech?.await()
            return speaks
        }

        override suspend fun listen(language: SpokenLanguage): String? {
            listened++
            return hears
        }
    }

    private val replies = mutableListOf<Pair<String, String>>()
    private val question = SpokenQuestion("rescuer", "Are you hurt?")

    private fun talkBack(voice: Voice, mayListen: Boolean = true) =
        TalkBack(voice, { mayListen }, { to, text -> replies += to to text })

    @Test
    fun theQuestionIsSpokenAndTheAnswerGoesBackAsText() = runTest {
        val voice = FakeVoice()
        talkBack(voice).ask(question)
        assertEquals("A rescuer is asking. Are you hurt? Please answer now.", voice.spoken.single().first)
        assertEquals(listOf("rescuer" to "Spoken answer: my leg is stuck"), replies)
    }

    @Test
    fun withoutOptInThePhoneSpeaksButNeverListens() = runTest {
        val voice = FakeVoice()
        talkBack(voice, mayListen = false).ask(question)
        assertEquals(1, voice.spoken.size)
        assertEquals(0, voice.listened)
        assertTrue(replies.single().second.contains("isn't allowed to listen"))
    }

    @Test
    fun aPhoneThatCanNotSpeakNeverListens() = runTest {
        val voice = FakeVoice(speaks = false)
        talkBack(voice).ask(question)
        assertEquals(0, voice.listened)
        assertTrue(replies.single().second.contains("couldn't read"))
    }

    @Test
    fun theRescuerIsToldWhenNothingWasHeard() = runTest {
        for (heard in listOf(null, "", "   ")) {
            replies.clear()
            talkBack(FakeVoice(hears = heard)).ask(question)
            assertTrue(replies.single().second.contains("heard no answer"))
        }
    }

    @Test
    fun aVeryLongAnswerStillFitsInOneMessage() = runTest {
        talkBack(FakeVoice(hears = "x".repeat(MAX_TEXT_CHARS + 50))).ask(question)
        assertEquals(MAX_TEXT_CHARS, replies.single().second.length)
    }

    @Test
    fun twoQuestionsTakeTurns() = runTest {
        val voice = FakeVoice()
        voice.holdSpeech = CompletableDeferred()
        val phone = talkBack(voice)
        launch { phone.ask(SpokenQuestion("rescuer", "First?")) }
        launch { phone.ask(SpokenQuestion("rescuer", "Second?")) }
        runCurrent()
        assertEquals(1, voice.spoken.size)
        voice.holdSpeech?.complete(Unit)
        runCurrent()
        assertEquals(2, voice.spoken.size)
        assertEquals(2, replies.size)
    }

    @Test
    fun theLanguageFollowsTheQuestion() {
        assertEquals(SpokenLanguage.ENGLISH, spokenLanguageOf("Are you hurt?"))
        assertEquals(SpokenLanguage.HINDI, spokenLanguageOf("क्या आपको चोट लगी है?"))
        assertEquals(SpokenLanguage.TELUGU, spokenLanguageOf("మీకు గాయం అయిందా?"))
        assertEquals(SpokenLanguage.TELUGU, spokenLanguageOf("Gate 3 దగ్గర ఉన్నారా?"))
    }

    @Test
    fun eachLanguageSpeaksItsOwnPrompt() {
        assertTrue(SpokenLanguage.HINDI.prompt("प्रश्न").contains("जवाब"))
        assertTrue(SpokenLanguage.TELUGU.prompt("ప్రశ్న").contains("సమాధానం"))
        assertFalse(SpokenLanguage.ENGLISH.prompt("Are you hurt?").contains("जवाब"))
    }
}
