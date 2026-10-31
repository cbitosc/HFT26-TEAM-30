package com.slackers.emergencymesh.background

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.slackers.emergencymesh.core.MAX_TEXT_CHARS
import com.slackers.emergencymesh.core.SpokenQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private const val ENGINE_START_MILLIS = 5_000L
private const val SPEAK_LIMIT_MILLIS = 30_000L
private const val LISTEN_LIMIT_MILLIS = 12_000L
private const val UTTERANCE_ID = "talk_back"

internal enum class SpokenLanguage(val tag: String, private val before: String, private val after: String) {
    ENGLISH("en-IN", "A rescuer is asking.", "Please answer now."),
    HINDI("hi-IN", "बचाव दल पूछ रहा है।", "कृपया अब जवाब दें।"),
    TELUGU("te-IN", "రక్షణ బృందం అడుగుతోంది.", "దయచేసి ఇప్పుడు సమాధానం చెప్పండి."),
    ;

    fun prompt(question: String) = "$before $question $after"
}

/** Questions are typed in the language to speak, so the script of the text decides it. */
internal fun spokenLanguageOf(text: String): SpokenLanguage = when {
    text.any { it in 'ఀ'..'౿' } -> SpokenLanguage.TELUGU
    text.any { it in 'ऀ'..'ॿ' } -> SpokenLanguage.HINDI
    else -> SpokenLanguage.ENGLISH
}

internal interface Voice {
    suspend fun speak(text: String, language: SpokenLanguage): Boolean
    suspend fun listen(language: SpokenLanguage): String?
}

/**
 * Reads a question aloud and sends back what it hears, so a person can answer without touching
 * the phone. Only text crosses the mesh. The rescuer always gets a reply, so a silent phone can't
 * be mistaken for a person who chose not to answer.
 */
internal class TalkBack(
    private val voice: Voice,
    private val mayListen: () -> Boolean,
    private val reply: suspend (toDeviceId: String, text: String) -> Unit,
) {
    private val oneAtATime = Mutex()

    suspend fun ask(question: SpokenQuestion) = oneAtATime.withLock {
        val language = spokenLanguageOf(question.text)
        val spoken = voice.speak(language.prompt(question.text), language)
        val text = when {
            !spoken -> "This phone couldn't read your question aloud."
            !mayListen() -> "This phone read your question aloud, but it isn't allowed to listen."
            else -> voice.listen(language)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { "Spoken answer: $it" }
                ?: "This phone read your question aloud, but heard no answer."
        }
        reply(question.fromDeviceId, text.take(MAX_TEXT_CHARS))
    }
}

/** Listening is a separate choice from sending an SOS, so it stays off until someone opts in. */
class TalkBackControl internal constructor(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("talk_back", Context.MODE_PRIVATE)
    private val _allowed = MutableStateFlow(preferences.getBoolean("allowed", false))

    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    fun setAllowed(on: Boolean) {
        preferences.edit().putBoolean("allowed", on).apply()
        _allowed.value = on
    }
}

/** Runs on the main thread, as SpeechRecognizer requires. The service's scope already does. */
internal class DeviceVoice(private val context: Context) : Voice {
    private val audio = context.getSystemService(AudioManager::class.java)

    override suspend fun speak(text: String, language: SpokenLanguage): Boolean {
        val engine = withTimeoutOrNull(ENGINE_START_MILLIS) { startEngine() } ?: return false
        val volumeBefore = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        try {
            // The alarm stream still plays when the phone is set to silent.
            runCatching {
                val loudest = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, loudest, 0)
            }
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            engine.language = Locale.forLanguageTag(language.tag)
            return withTimeoutOrNull(SPEAK_LIMIT_MILLIS) { say(engine, text) } == true
        } finally {
            volumeBefore?.let { volume ->
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
            }
            engine.shutdown()
        }
    }

    private suspend fun startEngine(): TextToSpeech? = suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (continuation.isActive) {
                continuation.resume(engine.takeIf { status == TextToSpeech.SUCCESS })
            }
        }
        continuation.invokeOnCancellation { engine.shutdown() }
    }

    private suspend fun say(engine: TextToSpeech, text: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finish(true)

                @Deprecated("Replaced by the version with an error code, which Android also calls.")
                override fun onError(utteranceId: String?) = finish(false)

                override fun onError(utteranceId: String?, errorCode: Int) = finish(false)

                private fun finish(spoken: Boolean) {
                    if (continuation.isActive) continuation.resume(spoken)
                }
            })
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) != TextToSpeech.SUCCESS) {
                continuation.resume(false)
            }
        }

    override suspend fun listen(language: SpokenLanguage): String? =
        withTimeoutOrNull(LISTEN_LIMIT_MILLIS) {
            val recognizer = createRecognizer() ?: return@withTimeoutOrNull null
            try {
                hear(recognizer, language)
            } finally {
                recognizer.destroy()
            }
        }

    /** Prefers the recognizer that works with no internet, which the mesh can't count on. */
    private fun createRecognizer(): SpeechRecognizer? = when {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
        else -> null
    }

    private suspend fun hear(recognizer: SpeechRecognizer, language: SpokenLanguage): String? =
        suspendCancellableCoroutine { continuation ->
            fun finish(text: String?) {
                if (continuation.isActive) continuation.resume(text)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) =
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())

                override fun onError(error: Int) = finish(null)
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            )
        }
}
