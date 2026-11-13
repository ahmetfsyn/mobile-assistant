package com.companion.assistant

import java.io.Closeable
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class WakeWordRecognition(val commandTranscript: String?)

internal object WakeWordConfidencePolicy {
    const val MINIMUM_CONFIDENCE = 0.55
    const val WAKE_ONLY_FALLBACK_CONFIDENCE = 0.90

    fun accepts(confidence: Double): Boolean = confidence >= MINIMUM_CONFIDENCE

    fun allowsWakeOnlyFallback(confidence: Double): Boolean = confidence >= WAKE_ONLY_FALLBACK_CONFIDENCE
}

/** A completed utterance must start with the standalone Turkish wake word Ayşe. */
internal object WakeWordMatcher {
    private val turkish = Locale("tr", "TR")
    private val firstToken = Regex("^([^\\s,.:!?;]+)")
    private val confusableWakeNames = setOf("aylin", "ayşegül", "ayşe'nin")

    fun isExactAyse(transcript: String?): Boolean {
        val recognition = match(transcript) ?: return false
        return recognition.commandTranscript == null
    }

    fun isKnownConfusableStart(transcript: String?): Boolean {
        val normalized = Normalizer.normalize(transcript.orEmpty().trim(), Normalizer.Form.NFC).lowercase(turkish)
        val token = firstToken.find(normalized)?.groupValues?.get(1)?.replace('’', '\'') ?: return false
        return token in confusableWakeNames
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

internal class WakeWordAudioStartException(val reason: String) : IllegalStateException(reason)

internal object WakeWordAudioStartRetryPolicy {
    const val MAX_RETRIES = 4

    fun shouldRetry(attempt: Int): Boolean = attempt in 1..MAX_RETRIES
}

/** Avoid a second full-model pass for a short, confident wake-only utterance. */
internal object WakeWordFastPathPolicy {
    const val MINIMUM_CONFIDENCE = 0.85
    const val MAX_AUDIO_DURATION_MS = 1_600L

    fun canSkipCommandConfirmation(confidence: Double, audioDurationMs: Long): Boolean =
        confidence >= MINIMUM_CONFIDENCE && audioDurationMs in 1..MAX_AUDIO_DURATION_MS
}

/** Blocking worker-thread loop. Audio, model, and exclusive microphone lease are always released. */
internal class WakeWordMonitor(
    private val source: WakeWordAudioSource,
    private val engine: WakeWordEngine,
    private val audioBuffer: ByteArray = ByteArray(DEFAULT_BUFFER_BYTES),
    private val onCaptureStarted: () -> Unit = {}
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)
    private val gate = WakeWordDetectionGate()
    private var lease: MicrophoneOwnership.Lease? = null

    @Throws(Exception::class)
    fun runUntilDetected(): WakeWordRecognition? {
        try {
            lease = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.WAKE_WORD)
                ?: throw WakeWordAudioStartException("MICROPHONE_BUSY")
            if (stopped.get()) return null
            if (!source.start()) throw WakeWordAudioStartException("AUDIO_RECORD_START_FAILED")
            onCaptureStarted()
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
