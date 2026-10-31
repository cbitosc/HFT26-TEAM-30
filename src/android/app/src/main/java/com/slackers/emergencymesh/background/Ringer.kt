package com.slackers.emergencymesh.background

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a ringing phone does. Hardware can refuse, so a failure here must never stop the ring. */
internal interface RingOutput {
    fun begin()

    /** Called on every flash, so short beeps can follow the torch. */
    fun beat(on: Boolean)
    fun end()
}

/**
 * Rings for a fixed time and then stops by itself, so a phone trapped under rubble can't drain its
 * battery. Short pulses of sound are easier for a person to locate than a steady tone.
 */
internal class Ringer(
    private val scope: CoroutineScope,
    private val output: RingOutput,
    private val ringMillis: Long = RING_MILLIS,
    private val flashMillis: Long = 250,
) {
    private var job: Job? = null

    fun ring() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                output.begin()
                repeat((ringMillis / flashMillis).toInt()) { flash ->
                    output.beat(flash % 2 == 0)
                    delay(flashMillis)
                }
            } finally {
                output.end()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    companion object {
        const val RING_MILLIS = 10_000L
    }
}

internal class DeviceRingOutput(context: Context) : RingOutput {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val torch: String? by lazy {
        runCatching {
            camera.cameraIdList.firstOrNull { id ->
                val details = camera.getCameraCharacteristics(id)
                details.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    details.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
    }
    private var tone: ToneGenerator? = null
    private var volumeBefore: Int? = null

    override fun begin() {
        // The alarm stream still plays when the phone is set to silent.
        runCatching {
            volumeBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        runCatching {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 250), 0), attributes)
        }
    }

    override fun beat(on: Boolean) {
        setTorch(on)
        if (on) runCatching { tone?.startTone(ToneGenerator.TONE_DTMF_D, BEEP_MILLIS) }
    }

    override fun end() {
        setTorch(false)
        runCatching { tone?.release() }
        tone = null
        runCatching { vibrator.cancel() }
        volumeBefore?.let { volume ->
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        volumeBefore = null
    }

    private fun setTorch(on: Boolean) {
        val id = torch ?: return
        runCatching { camera.setTorchMode(id, on) }
    }

    private companion object {
        const val BEEP_MILLIS = 200
    }
}
