package com.companion.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/** Owns one temporary audio-route session and restores the host audio state on close. */
internal class AudioOutputDiagnosticController(
    context: Context,
    private val onStatusChanged: (String) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AudioDiagnostic").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()
    private val routeLock = Any()
    private val originalMode = audioManager.mode
    private val originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
    private val tone = createTestTone()

    @Volatile private var closed = false
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var activeOutput: AudioDiagnosticView.Output? = null
    private var currentTask: Future<*>? = null
    private var completionCallback: Runnable? = null

    init {
        onStatusChanged(status("—", isWiredHeadsetConnected(), "Bir çıkış seçin."))
    }

    fun testOutput(output: AudioDiagnosticView.Output) {
        if (closed) return

        val token = generation.incrementAndGet()
        val wiredConnected = isWiredHeadsetConnected()
        currentTask?.cancel(true)
        cancelCompletionCallback()
        stopAndReleaseActiveTrack("Önceki test durduruldu.")

        Log.i(TAG, "İstenen çıkış: ${output.label}; wiredHeadset=$wiredConnected; mode=MODE_IN_COMMUNICATION")
        onStatusChanged(status(output.label, wiredConnected, "Test hazırlanıyor…"))

        if (output == AudioDiagnosticView.Output.HEADPHONES && !wiredConnected) {
            val message = "Kablolu kulaklık bağlı değil; ses yönlendirilmedi."
            Log.w(TAG, "İstenen çıkış: ${output.label}; sonuç: $message")
            onStatusChanged(status(output.label, wiredConnected, message))
            return
        }

        try {
            currentTask = worker.submit { startPlayback(output, wiredConnected, token) }
        } catch (error: Exception) {
            reportFailure(output, wiredConnected, token, "Ses testi başlatılamadı.", error)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        currentTask?.cancel(true)
        currentTask = null
        cancelCompletionCallback()
        worker.shutdownNow()

        synchronized(routeLock) {
            stopAndReleaseActiveTrack("Tanılama kapatıldı.")
            try {
                audioManager.mode = originalMode
                audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
                Log.i(TAG, "Tanılama kapandı; ses modu ve hoparlör durumu geri yüklendi.")
            } catch (error: Exception) {
                Log.e(TAG, "Özgün ses yönlendirmesi geri yüklenemedi.", error)
            }
        }
    }

    private fun startPlayback(
        output: AudioDiagnosticView.Output,
        wiredConnected: Boolean,
        token: Int
    ) {
        var track: AudioTrack? = null
        try {
            synchronized(routeLock) {
                if (!isCurrent(token)) return

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = output == AudioDiagnosticView.Output.SPEAKER

                val device = findPreferredDevice(output)
                    ?: throw IllegalStateException("İstenen ses çıkış aygıtı kullanılamıyor.")

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(tone.size)
                    .build()

                if (track?.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("Ses oynatıcısı başlatılamadı.")
                }
                if (!isCurrent(token)) throw InterruptedException("Test iptal edildi.")

                if (track?.setPreferredDevice(device) != true) {
                    throw IllegalStateException("İstenen ses çıkışına yönlendirme yapılamadı.")
                }

                activeTrack = track
                activeOutput = output
                var offset = 0
                while (offset < tone.size) {
                    if (!isCurrent(token)) throw InterruptedException("Test iptal edildi.")
                    val written = track?.write(tone, offset, tone.size - offset, AudioTrack.WRITE_BLOCKING) ?: -1
                    if (written <= 0) throw IllegalStateException("Test sesi yazılamadı.")
                    offset += written
                }

                if (!isCurrent(token)) throw InterruptedException("Test iptal edildi.")
                track?.play()
                if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    throw IllegalStateException("Ses oynatımı başlatılamadı.")
                }
            }

            if (!isCurrent(token)) return
            Log.i(TAG, "İstenen çıkış: ${output.label}; sonuç: oynatım başladı.")
            val startedMessage = status(output.label, wiredConnected, "Test sesi çalıyor…")
            val completion = Runnable { finishPlayback(output, wiredConnected, token) }
            mainHandler.post {
                if (!isCurrent(token)) return@post
                onStatusChanged(startedMessage)
                completionCallback = completion
                mainHandler.postDelayed(completion, TONE_DURATION_MS + 120L)
            }
        } catch (error: Exception) {
            synchronized(routeLock) {
                track?.let { releaseTrack(it) }
                if (activeTrack === track) {
                    activeTrack = null
                    activeOutput = null
                }
            }
            if (error !is InterruptedException && isCurrent(token)) {
                reportFailure(output, wiredConnected, token, error.message ?: "Ses testi başlatılamadı.", error)
            }
        }
    }

    private fun finishPlayback(
        output: AudioDiagnosticView.Output,
        wiredConnected: Boolean,
        token: Int
    ) {
        completionCallback = null
        if (!isCurrent(token)) return

        val track = activeTrack
        val playedFrames = try {
            track?.playbackHeadPosition ?: 0
        } catch (_: IllegalStateException) {
            0
        }
        stopAndReleaseActiveTrack()

        val completed = playedFrames >= FRAME_COUNT * 9 / 10
        val result = if (completed) "Ses testi tamamlandı." else "Ses testi erken sona erdi."
        if (completed) Log.i(TAG, "İstenen çıkış: ${output.label}; sonuç: $result")
        else Log.w(TAG, "İstenen çıkış: ${output.label}; sonuç: $result (frames=$playedFrames)")
        onStatusChanged(status(output.label, wiredConnected, result))
    }

    private fun reportFailure(
        output: AudioDiagnosticView.Output,
        wiredConnected: Boolean,
        token: Int,
        message: String,
        error: Exception
    ) {
        if (!isCurrent(token)) return
        Log.e(TAG, "İstenen çıkış: ${output.label}; sonuç: $message", error)
        mainHandler.post {
            if (isCurrent(token)) onStatusChanged(status(output.label, wiredConnected, message))
        }
    }

    private fun findPreferredDevice(output: AudioDiagnosticView.Output): AudioDeviceInfo? {
        val type = when (output) {
            AudioDiagnosticView.Output.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            AudioDiagnosticView.Output.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            AudioDiagnosticView.Output.HEADPHONES -> null
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return if (output == AudioDiagnosticView.Output.HEADPHONES) {
            devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
        } else {
            devices.firstOrNull { it.type == type }
        }
    }

    private fun isWiredHeadsetConnected(): Boolean {
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
        } catch (error: Exception) {
            Log.w(TAG, "Kablolu kulaklık durumu okunamadı.", error)
            false
        }
    }

    private fun status(output: String, wiredConnected: Boolean, result: String): String =
        "Seçilen çıkış: $output\n" +
            "Ses modu: ${audioModeLabel(audioManager.mode)}\n" +
            "Kablolu kulaklık: ${if (wiredConnected) "Bağlı" else "Bağlı değil"}\n" +
            "Durum: $result"

    private fun audioModeLabel(mode: Int): String = when (mode) {
        AudioManager.MODE_IN_COMMUNICATION -> "İletişim"
        AudioManager.MODE_IN_CALL -> "Arama"
        AudioManager.MODE_RINGTONE -> "Zil sesi"
        else -> "Normal"
    }

    private fun isCurrent(token: Int): Boolean = !closed && generation.get() == token

    private fun cancelCompletionCallback() {
        completionCallback?.let(mainHandler::removeCallbacks)
        completionCallback = null
    }

    private fun stopAndReleaseActiveTrack(reason: String? = null) {
        synchronized(routeLock) {
            val track = activeTrack
            val output = activeOutput
            activeTrack = null
            activeOutput = null
            if (track != null) {
                if (reason != null && output != null) {
                    Log.i(TAG, "İstenen çıkış: ${output.label}; sonuç: $reason")
                }
                releaseTrack(track)
            }
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING
            ) {
                track.stop()
            }
        } catch (_: IllegalStateException) {
            // The track may already have stopped or been released by a competing cancellation.
        }
        try {
            track.release()
        } catch (_: IllegalStateException) {
            // Release is best effort when the playback thread is already shutting down.
        }
    }

    private fun createTestTone(): ByteArray {
        val bytes = ByteArray(FRAME_COUNT * 2)
        val fadeFrames = SAMPLE_RATE / 40
        for (frame in 0 until FRAME_COUNT) {
            val fadeIn = (frame.toFloat() / fadeFrames).coerceAtMost(1f)
            val fadeOut = ((FRAME_COUNT - 1 - frame).toFloat() / fadeFrames).coerceAtMost(1f)
            val envelope = minOf(fadeIn, fadeOut)
            val sample = (sin(2.0 * PI * TONE_FREQUENCY * frame / SAMPLE_RATE) *
                TONE_AMPLITUDE * envelope * Short.MAX_VALUE).toInt().toShort()
            bytes[frame * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[frame * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return bytes
    }

    private companion object {
        const val TAG = "AudioDiagnostic"
        const val SAMPLE_RATE = 16_000
        const val TONE_FREQUENCY = 660.0
        const val TONE_AMPLITUDE = 0.24
        const val TONE_DURATION_MS = 1_500L
        const val FRAME_COUNT = SAMPLE_RATE * 3 / 2
    }
}
