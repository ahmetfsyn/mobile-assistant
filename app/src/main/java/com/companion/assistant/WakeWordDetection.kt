package com.companion.assistant

import java.io.Closeable
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class WakeWordRecognition(val commandTranscript: String?)

internal object WakeWordConfidencePolicy {
    const val MINIMUM_CONFIDENCE = 0.65

    fun accepts(confidence: Double): Boolean = confidence >= MINIMUM_CONFIDENCE
}

/** A completed utterance must start with the standalone Turkish wake word Ayşe. */
internal object WakeWordMatcher {
    private val turkish = Locale("tr", "TR")
    private val firstToken = Regex("^([^\\s,.:!?;]+)")

    fun isExactAyse(transcript: String?): Boolean {
        val recognition = match(transcript) ?: return false
        return recognition.commandTranscript == null
    }

    fun match(transcript: String?): WakeWordRecognition? {
        val normalized = Normalizer.normalize(transcript.orEmpty().trim(), Normalizer.Form.NFC).lowercase(turkish)
        val match = firstToken.find(normalized) ?: return null
        if (match.groupValues[1].trimEnd('.', ',', '!', '?', ':', ';') != "ayşe") return null
        val remainder = normalized.substring(match.range.last + 1).trimStart { char ->
            char.isWhitespace() || char == ',' || char == '.' || char == '!' || char == '?' || char == ':' || char == ';'
        }.trim().trimEnd('.', ',', '!', '?', ':', ';')
        return WakeWordRecognition(remainder.takeIf(String::isNotBlank))
    }
}

/** Prevents repeated callbacks from one recognition session from emitting multiple wake events. */
internal class WakeWordDetectionGate {
    private var emitted = false

    @Synchronized
    fun acceptFinalResult(transcript: String?): WakeWordRecognition? {
        if (emitted) return null
        val recognition = WakeWordMatcher.match(transcript) ?: return null
        emitted = true
        return recognition
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
    /** Returns a completed local utterance when it begins with the configured wake phrase. */
    fun acceptAudio(pcm16le: ByteArray, length: Int): String?
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
    fun runUntilDetected(): WakeWordRecognition? {
        try {
            lease = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.WAKE_WORD)
                ?: throw IllegalStateException("Mikrofon başka bir işlem tarafından kullanılıyor")
            if (stopped.get()) return null
            if (!source.start()) throw IllegalStateException("Mikrofon kaydı başlatılamadı")
            while (!stopped.get()) {
                val count = source.read(audioBuffer)
                if (stopped.get()) return null
                if (count < 0) throw IllegalStateException("Mikrofon ses verisi okunamadı")
                if (count > audioBuffer.size) throw IllegalStateException("Mikrofon geçersiz ses arabelleği döndürdü")
                if (count == 0) continue
                val alignedCount = count and -2 // PCM16 needs complete samples; defer an incomplete last byte.
                if (alignedCount == 0) continue
                // Partial hypotheses are unstable and can temporarily turn similar names into Ayşe.
                val recognition = gate.acceptFinalResult(engine.acceptAudio(audioBuffer, alignedCount))
                if (recognition != null) return recognition
            }
            return null
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
