package com.companion.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.util.Log
import java.io.Closeable

/** Microphone input source. Platform recognition returns text; PCM sources return an in-memory recording. */
internal interface SpeechInput : Closeable {
    interface Callback {
        fun onRecordingReady(sessionId: Long, recording: AudioRecording)
        fun onTranscriptReady(sessionId: Long, transcript: String)
        fun onError(sessionId: Long, message: String)
    }

    fun startListening(sessionId: Long, language: VoiceLanguage, callback: Callback)
    fun cancel()
}

internal enum class SpeechInputMode { PLATFORM, CLOUD_PCM, MOCK_PCM }

internal data class SpeechInputConfiguration(
    val input: SpeechInput,
    /** Non-null only when the selected input returns PCM for recognition on the worker thread. */
    val recordedSpeechRecognizer: SpeechRecognizer?
)

internal object SpeechInputFactory {
    fun modeFor(endpoint: String, useMock: Boolean): SpeechInputMode = when {
        endpoint.isNotBlank() -> SpeechInputMode.CLOUD_PCM
        useMock -> SpeechInputMode.MOCK_PCM
        else -> SpeechInputMode.PLATFORM
    }

    fun create(context: Context): SpeechInputConfiguration {
        val endpoint = VoiceConfig.cloudSpeechEndpoint.trim()
        return when (modeFor(endpoint, VoiceConfig.useMockSpeechRecognition)) {
            SpeechInputMode.PLATFORM -> {
                Log.i(
                    TAG,
                    "Android sistem konuşma tanıma seçildi (dil=${VoiceConfig.language.tag}; çevrimdışı tercih, ağ kullanımı servise bağlı)."
                )
                SpeechInputConfiguration(PlatformSpeechInput(context.applicationContext), null)
            }

            SpeechInputMode.CLOUD_PCM -> {
                Log.i(TAG, "HTTPS PCM konuşma tanıma servisi seçildi.")
                SpeechInputConfiguration(
                    PcmSpeechInput(AudioRecorder()),
                    HttpSpeechRecognizer(endpoint, VoiceConfig.cloudSpeechBearerToken.trim())
                )
            }

            SpeechInputMode.MOCK_PCM -> {
                Log.i(TAG, "Açıkça etkinleştirilmiş geliştirme mock konuşma tanıma seçildi.")
                SpeechInputConfiguration(PcmSpeechInput(AudioRecorder()), MockSpeechRecognizer())
            }
        }
    }

    private const val TAG = "VoiceInteraction"
}

/** PCM path retained for the configured HTTPS endpoint and explicit mock development mode. */
internal class PcmSpeechInput(
    private val audioRecorder: AudioRecorder
) : SpeechInput {
    @Volatile private var activeSessionId: Long? = null
    @Volatile private var closed = false

    override fun startListening(sessionId: Long, language: VoiceLanguage, callback: SpeechInput.Callback) {
        if (closed) {
            callback.onError(sessionId, "SES TANIMA KAPALI")
            return
        }
        cancel()
        activeSessionId = sessionId
        val started = try {
            audioRecorder.start(object : AudioRecorder.Callback {
                override fun onRecordingComplete(recording: AudioRecording) {
                    if (closed || activeSessionId != sessionId) {
                        recording.close()
                        return
                    }
                    callback.onRecordingReady(sessionId, recording)
                }

                override fun onRecordingError(message: String) {
                    if (!closed && activeSessionId == sessionId) callback.onError(sessionId, message)
                }
            })
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!started) {
            activeSessionId = null
            callback.onError(sessionId, "MİKROFON BAŞLATILAMADI")
        }
    }

    override fun cancel() {
        activeSessionId = null
        audioRecorder.cancel()
    }

    override fun close() {
        closed = true
        cancel()
    }
}

/** Android's installed recognizer owns the microphone for this push-to-talk session. */
internal class PlatformSpeechInput(context: Context) : SpeechInput {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var closed = false
    private var generation = 0L
    private var activeRecognizer: android.speech.SpeechRecognizer? = null

    override fun startListening(sessionId: Long, language: VoiceLanguage, callback: SpeechInput.Callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListening(sessionId, language, callback) }
            return
        }
        if (closed) {
            callback.onError(sessionId, "SES TANIMA KAPALI")
            return
        }

        cancelOnMainThread()
        val requestGeneration = ++generation
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(appContext)) {
            callback.onError(sessionId, "BU CİHAZDA KONUŞMA TANIMA SERVİSİ BULUNAMADI")
            return
        }

        val recognizer = try {
            android.speech.SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (_: SecurityException) {
            callback.onError(sessionId, "MİKROFON İZNİ GEREKLİ")
            return
        } catch (_: RuntimeException) {
            callback.onError(sessionId, "KONUŞMA TANIMA SERVİSİ BAŞLATILAMADI")
            return
        }
        activeRecognizer = recognizer

        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    finishRequest(recognizer, requestGeneration) {
                        callback.onTranscriptReady(sessionId, transcript)
                    }
                }

                override fun onError(error: Int) {
                    finishRequest(recognizer, requestGeneration) {
                        callback.onError(sessionId, messageFor(error))
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer.startListening(intent)
        } catch (_: SecurityException) {
            finishRequest(recognizer, requestGeneration) {
                callback.onError(sessionId, "MİKROFON İZNİ GEREKLİ")
            }
        } catch (_: RuntimeException) {
            finishRequest(recognizer, requestGeneration) {
                callback.onError(sessionId, "KONUŞMA TANIMA SERVİSİ BAŞLATILAMADI")
            }
        }
    }

    override fun cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelOnMainThread()
        } else {
            mainHandler.post { cancelOnMainThread() }
        }
    }

    override fun close() {
        closed = true
        cancel()
    }

    private fun finishRequest(
        recognizer: android.speech.SpeechRecognizer,
        requestGeneration: Long,
        callback: () -> Unit
    ) {
        if (requestGeneration != generation || activeRecognizer !== recognizer) return
        activeRecognizer = null
        try {
            recognizer.destroy()
        } catch (_: RuntimeException) {
            // A completed platform recognizer may already have released its service connection.
        }
        callback()
    }

    private fun cancelOnMainThread() {
        generation += 1
        val recognizer = activeRecognizer ?: return
        activeRecognizer = null
        try {
            recognizer.cancel()
        } catch (_: RuntimeException) {
            // destroy() still releases the service connection if cancellation raced with a callback.
        }
        try {
            recognizer.destroy()
        } catch (_: RuntimeException) {
            // The service may already have disconnected.
        }
    }

    private fun messageFor(error: Int): String = when (error) {
        android.speech.SpeechRecognizer.ERROR_AUDIO -> "SES GİRİŞİ BAŞLATILAMADI"
        android.speech.SpeechRecognizer.ERROR_CLIENT -> "SES TANIMA OTURUMU KESİLDİ"
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MİKROFON İZNİ GEREKLİ"
        android.speech.SpeechRecognizer.ERROR_NETWORK -> "KONUŞMA TANIMA SERVİSİNE AĞ ÜZERİNDEN ULAŞILAMADI"
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "KONUŞMA TANIMA AĞ ZAMAN AŞIMI"
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "KONUŞMA ANLAŞILAMADI"
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "KONUŞMA TANIMA MEŞGUL; TEKRAR DENE"
        android.speech.SpeechRecognizer.ERROR_SERVER -> "KONUŞMA TANIMA SERVİSİ YANIT VERMEDİ"
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SES ALGILANAMADI"
        else -> "KONUŞMA TANIMA HATASI ($error)"
    }
}
