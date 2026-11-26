package com.cloudwhile.tscc.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.cloudwhile.tscc.data.AppSettings
import com.cloudwhile.tscc.data.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency 48,000 Hz 20ms (960 samples) full-duplex Android VoIP audio pump.
 * Uses DirectByteBuffers for zero-copy PCM transfer with the Rust Opus/Mixer engine.
 */
class AndroidAudioIo(
    private val context: Context,
    private val onAudioFocusRestored: () -> Unit = {}
) {

    companion object {
        private const val TAG = "AndroidAudioIo"
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz
        const val FRAME_BYTES = FRAME_SAMPLES * 2 // 16-bit mono PCM
    }

    private val running = AtomicBoolean(false)
    private val captureRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    private val captureDirectBuf: ByteBuffer =
        ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val playbackDirectBuf: ByteBuffer =
        ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    private var audioFocusRequest: AudioFocusRequest? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile
    private var outputDeafened: Boolean = false

    @Volatile
    private var focusLostPaused: Boolean = false

    fun setOutputDeafened(deafened: Boolean) {
        outputDeafened = deafened
    }

    fun start(settings: AppSettings) {
        if (!NativeBridge.ensureLoaded()) return
        if (!running.compareAndSet(false, true)) {
            ensureCaptureStarted(settings)
            return
        }

        focusLostPaused = false
        NativeBridge.nativeAttachAudioBuffers(captureDirectBuf, playbackDirectBuf)
        requestCommunicationAudioFocus()

        playbackThread = Thread({ runPlaybackLoop() }, "TS-AudioPlayback").apply {
            isDaemon = true
            start()
        }
        ensureCaptureStarted(settings)
    }

    /**
     * Dynamically starts microphone capture if [Manifest.permission.RECORD_AUDIO] was just granted
     * mid-call while playback is already running in listen-only mode.
     */
    fun ensureCaptureStarted(settings: AppSettings) {
        if (!running.get()) return
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) return

        if (captureRunning.compareAndSet(false, true)) {
            captureThread = Thread({ runCaptureLoop(settings) }, "TS-AudioCapture").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun updateHardwareEffects(settings: AppSettings) {
        runCatching { aec?.enabled = settings.hardwareAecEnabled }
        runCatching { ns?.enabled = settings.hardwareNsEnabled }
        runCatching { agc?.enabled = settings.hardwareAgcEnabled }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        captureRunning.set(false)
        focusLostPaused = false
        captureThread?.interrupt()
        playbackThread?.interrupt()
        runCatching { captureThread?.join(400) }
        runCatching { playbackThread?.join(400) }
        captureThread = null
        playbackThread = null
        releaseEffects()
        abandonCommunicationAudioFocus()
    }

    private fun runCaptureLoop(settings: AppSettings) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            Log.w(TAG, "RECORD_AUDIO permission not granted; running in listen-only mode")
            captureRunning.set(false)
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord: ${se.message}")
            captureRunning.set(false)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            captureRunning.set(false)
            return
        }

        attachHardwareEffects(record.audioSessionId, settings)

        try {
            record.startRecording()
            while (running.get() && captureRunning.get() && !Thread.currentThread().isInterrupted) {
                if (focusLostPaused) {
                    Thread.sleep(20)
                    continue
                }
                captureDirectBuf.clear()
                var totalRead = 0
                while (totalRead < FRAME_BYTES && running.get() && !focusLostPaused) {
                    captureDirectBuf.position(totalRead)
                    val n = record.read(captureDirectBuf, FRAME_BYTES - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                if (totalRead == FRAME_BYTES && !focusLostPaused) {
                    NativeBridge.nativeOnCaptureFrameReady()
                } else {
                    Thread.sleep(5)
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown
        } catch (t: Throwable) {
            Log.e(TAG, "Capture loop error: ${t.message}")
        } finally {
            captureRunning.set(false)
            releaseEffects()
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun runPlaybackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val silenceBytes = ByteArray(FRAME_BYTES)

        try {
            track.play()
            while (running.get() && !Thread.currentThread().isInterrupted) {
                playbackDirectBuf.clear()
                val activeCount = NativeBridge.nativePullPlaybackFrame()
                if (outputDeafened || focusLostPaused || activeCount <= 0) {
                    track.write(silenceBytes, 0, FRAME_BYTES)
                } else {
                    playbackDirectBuf.position(0)
                    playbackDirectBuf.limit(FRAME_BYTES)
                    track.write(playbackDirectBuf, FRAME_BYTES, AudioTrack.WRITE_BLOCKING)
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown
        } catch (t: Throwable) {
            Log.e(TAG, "Playback loop error: ${t.message}")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun attachHardwareEffects(sessionId: Int, settings: AppSettings) {
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = settings.hardwareAecEnabled
                }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching {
                NoiseSuppressor.create(sessionId)?.apply {
                    enabled = settings.hardwareNsEnabled
                }
            }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching {
                AutomaticGainControl.create(sessionId)?.apply {
                    enabled = settings.hardwareAgcEnabled
                }
            }.getOrNull()
        }
    }

    private fun releaseEffects() {
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null
        ns = null
        agc = null
    }

    private fun requestCommunicationAudioFocus() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusLostPaused = true
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusLostPaused = false
                        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
                        onAudioFocusRestored()
                    }
                }
            }
            .build()
        audioFocusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonCommunicationAudioFocus() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        audioFocusRequest?.let { req ->
            runCatching { am.abandonAudioFocusRequest(req) }
        }
        audioFocusRequest = null
        am.mode = AudioManager.MODE_NORMAL
    }
}
