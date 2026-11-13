package com.companion.assistant

import java.io.Closeable
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Normalizes Turkish wake text and matches the exact phrase or a leading Ayşe command prefix. */
internal object WakeWordMatcher {
    private val turkish = Locale("tr", "TR")
    private val leadingAyse = Regex("^ayşe(?:$|[\\s,.;:!?])")

    fun isExactAyse(transcript: String?): Boolean {
        return normalize(transcript) == "ayşe"
    }

    fun startsWithAyse(transcript: String?): Boolean =
        !transcript.isNullOrBlank() && leadingAyse.containsMatchIn(normalize(transcript))

    private fun normalize(transcript: String?): String = transcript.orEmpty().let {
        Normalizer.normalize(it.trim(), Normalizer.Form.NFC)
            .trimEnd { char -> char == '.' || char == ',' || char == '!' || char == '?' || char == ':' || char == ';' }
            .lowercase(turkish)
    }
}

/** Prevents repeated callbacks from one recognition session from emitting multiple wake events. */
internal class WakeWordDetectionGate {
    private var emitted = false

    @Synchronized
    fun acceptFinalResult(transcript: String?): Boolean {
        if (emitted || !WakeWordMatcher.isExactAyse(transcript)) return false
        emitted = true
        return true
    }

    @Synchronized
    fun acceptAyseCommandStart(transcript: String?): Boolean {
        if (emitted || !WakeWordMatcher.startsWithAyse(transcript)) return false
        emitted = true
        return true
    }
}

internal interface WakeWordAudioSource : Closeable {
    /** Start capture, returning false if the recorder did not enter RECORDSTATE_RECORDING. */
    fun start(): Boolean
    /** Reads PCM16 little-endian bytes into the supplied reusable buffer. */
    fun read(buffer: ByteArray): Int
    /** Must unblock a pending read when possible. */
    fun stop()
}

internal interface WakeWordEngine : Closeable {
    /** Returns the latest completed local result when available. */
    fun acceptAudio(pcm16le: ByteArray, length: Int): String?
    /** Current local partial text, if the engine exposes one. */
    fun partialTranscript(): String? = null
}

/** Blocking worker-thread loop. Audio, model, and exclusive microphone lease are always released. */
internal class WakeWordMonitor(
    private val source: WakeWordAudioSource,
    private val engine: WakeWordEngine,
    private val audioBuffer: ByteArray = ByteArray(DEFAULT_BUFFER_BYTES)
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)
    private val gate = WakeWordDetectionGate()
    private var lease: MicrophoneOwnership.Lease? = null

    @Throws(Exception::class)
    fun runUntilDetected(): Boolean {
        try {
            lease = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.WAKE_WORD)
                ?: throw IllegalStateException("Mikrofon başka bir işlem tarafından kullanılıyor")
            if (stopped.get()) return false
            if (!source.start()) throw IllegalStateException("Mikrofon kaydı başlatılamadı")
            while (!stopped.get()) {
                val count = source.read(audioBuffer)
                if (stopped.get()) return false
                if (count < 0) throw IllegalStateException("Mikrofon ses verisi okunamadı")
                if (count > audioBuffer.size) throw IllegalStateException("Mikrofon geçersiz ses arabelleği döndürdü")
                if (count == 0) continue
                val alignedCount = count and -2 // PCM16 needs complete samples; defer an incomplete last byte.
                if (alignedCount == 0) continue
                val finalTranscript = engine.acceptAudio(audioBuffer, alignedCount)
                if (gate.acceptFinalResult(finalTranscript) ||
                    gate.acceptAyseCommandStart(finalTranscript) ||
                    gate.acceptAyseCommandStart(engine.partialTranscript())
                ) return true
            }
            return false
        } finally {
            releaseResources()
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        try { source.stop() } catch (_: Exception) { }
        try { source.close() } catch (_: Exception) { }
        releaseMicrophone()
    }

    private fun releaseResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        try { source.stop() } catch (_: Exception) { }
        try { source.close() } catch (_: Exception) { }
        releaseMicrophone()
        try { engine.close() } catch (_: Exception) { }
        audioBuffer.fill(0)
    }

    private fun releaseMicrophone() {
        lease?.close()
        lease = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_BUFFER_BYTES = 4_096
    }
}
