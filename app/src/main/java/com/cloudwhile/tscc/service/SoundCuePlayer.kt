package com.cloudwhile.tscc.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

enum class SoundCueType {
    CONNECTED,
    SWITCH_CHANNEL,
    DISCONNECTED,
    PEER_JOIN,
    PEER_LEAVE
}

/**
 * Zero-asset synthesized 48kHz 16-bit PCM earcon & haptic feedback player.
 * Uses raised-cosine attack and smooth exponential decay envelopes to avoid clicks/pops,
 * routed over USAGE_VOICE_COMMUNICATION to follow Bluetooth/earpiece/speaker voice routing.
 */
class SoundCuePlayer(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TS-SoundCue").apply { isDaemon = true }
    }

    private val prebuiltPcm: Map<SoundCueType, ShortArray> by lazy {
        mapOf(
            SoundCueType.CONNECTED to synthesizeTwoToneChimePcm(
                freq1Hz = 523.25, // C5
                dur1Ms = 68,
                freq2Hz = 783.99, // G5
                dur2Ms = 110,
                peakGain = 0.22
            ),
            SoundCueType.SWITCH_CHANNEL to synthesizeTwoToneChimePcm(
                freq1Hz = 587.33, // D5
                dur1Ms = 48,
                freq2Hz = 880.00, // A5
                dur2Ms = 78,
                peakGain = 0.17
            ),
            SoundCueType.DISCONNECTED to synthesizeTwoToneChimePcm(
                freq1Hz = 659.25, // E5
                dur1Ms = 65,
                freq2Hz = 440.00, // A4
                dur2Ms = 115,
                peakGain = 0.20
            ),
            SoundCueType.PEER_JOIN to synthesizeTwoToneChimePcm(
                freq1Hz = 587.33, // D5
                dur1Ms = 45,
                freq2Hz = 739.99, // F#5
                dur2Ms = 70,
                peakGain = 0.12
            ),
            SoundCueType.PEER_LEAVE to synthesizeTwoToneChimePcm(
                freq1Hz = 739.99, // F#5
                dur1Ms = 45,
                freq2Hz = 554.37, // C#5
                dur2Ms = 72,
                peakGain = 0.11
            )
        )
    }

    fun playCue(
        type: SoundCueType,
        soundEnabled: Boolean,
        outputDeafened: Boolean,
        withHaptic: Boolean = false
    ) {
        if (withHaptic && canVibrate()) {
            vibrateTick(durationMs = if (type == SoundCueType.DISCONNECTED) 28L else 18L)
        }
        if (!canPlayAudibleCue(soundEnabled, outputDeafened)) return

        executor.execute {
            runCatching {
                if (!canPlayAudibleCue(soundEnabled, outputDeafened)) return@execute
                val samples = prebuiltPcm[type] ?: return@execute
                val byteLen = samples.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(byteLen.coerceAtLeast(2048))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()
                val durationMs = (samples.size * 1000L / SAMPLE_RATE) + 35L
                Thread.sleep(durationMs)
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    fun vibrateError() {
        if (!canVibrate()) return
        runCatching {
            getVibrator()?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 36, 48, 52), -1)
            )
        }
    }

    fun vibrateTick(durationMs: Long = 18L) {
        if (!canVibrate()) return
        runCatching {
            getVibrator()?.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    /**
     * Strictly enforces system Silent / Vibrate / Do-Not-Disturb modes and notification stream mute,
     * since USAGE_VOICE_COMMUNICATION otherwise bypasses Android's ringer switch.
     */
    fun canPlayAudibleCue(soundEnabled: Boolean, outputDeafened: Boolean): Boolean {
        if (!soundEnabled || outputDeafened) return false
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return false
        }
        val notifVol = runCatching { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrDefault(1)
        val ringVol = runCatching { am.getStreamVolume(AudioManager.STREAM_RING) }.getOrDefault(1)
        if (notifVol <= 0 && ringVol <= 0) {
            return false
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null) {
            val filter = nm.currentInterruptionFilter
            if (filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
                filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
            ) {
                return false
            }
        }
        return true
    }

    fun canVibrate(): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return false
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null) {
            val filter = nm.currentInterruptionFilter
            if (filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
            ) {
                return false
            }
        }
        return true
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    companion object {
        internal const val SAMPLE_RATE = 48_000

        internal fun synthesizeTwoToneChimePcm(
            freq1Hz: Double,
            dur1Ms: Int,
            freq2Hz: Double,
            dur2Ms: Int,
            peakGain: Double
        ): ShortArray {
            val n1 = (SAMPLE_RATE * dur1Ms) / 1000
            val n2 = (SAMPLE_RATE * dur2Ms) / 1000
            val total = (n1 + n2).coerceAtLeast(1)
            val out = ShortArray(total)
            val attackSamples = (SAMPLE_RATE * 6) / 1000 // 6ms smooth raised-cosine fade-in

            fun renderSegment(offset: Int, len: Int, freq: Double) {
                for (i in 0 until len) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val attackEnv = if (i < attackSamples && attackSamples > 0) {
                        0.5 * (1.0 - cos(PI * i.toDouble() / attackSamples))
                    } else {
                        1.0
                    }
                    val decayEnv = exp(-3.4 * i.toDouble() / len.coerceAtLeast(1))
                    val fundamental = sin(2.0 * PI * freq * t)
                    val overtone = 0.22 * sin(2.0 * PI * (freq * 2.0) * t)
                    val sample = (fundamental + overtone) * 0.82 * peakGain * attackEnv * decayEnv
                    out[offset + i] = (sample * 32767.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
                }
            }

            renderSegment(0, n1, freq1Hz)
            renderSegment(n1, n2, freq2Hz)
            return out
        }
    }
}
