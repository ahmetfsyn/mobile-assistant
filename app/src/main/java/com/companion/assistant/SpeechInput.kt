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
        fun onSpeechStarted(sessionId: Long) = Unit
        fun onSpeechEnded(sessionId: Long) = Unit
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
                    "Android sistem konuşma tanıma seçildi (dil=${VoiceConfig.language.tag}; offline-only=${VoiceConfig.preferOfflineSpeechRecognition})."
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
                override fun onSpeechStarted() {
                    if (!closed && activeSessionId == sessionId) callback.onSpeechStarted(sessionId)
                }

                override fun onRecordingComplete(recording: AudioRecording) {
                    if (closed || activeSessionId != sessionId) {
                        recording.close()
                        return
                    }
                    callback.onSpeechEnded(sessionId)
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

/** Boundary around Android SpeechRecognizer so lifecycle behavior can be tested without a device. */
internal interface SpeechRecognitionEngine {
    interface Listener {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onEndOfSpeech()
        fun onResults(transcript: String)
        fun onError(errorCode: Int)
    }

    fun startListening(language: VoiceLanguage, preferOffline: Boolean, listener: Listener)
    fun cancel()
    fun destroy()
}

internal interface SpeechRecognitionEngineFactory {
    fun isAvailable(): Boolean
    fun create(): SpeechRecognitionEngine
}

internal interface SpeechInputDispatcher {
    fun dispatch(action: () -> Unit)
}

internal interface SpeechInputDiagnostics {
    fun info(message: String)
    fun warning(message: String)
    fun error(message: String)
    fun debug(message: String)
}

internal object AndroidSpeechInputDiagnostics : SpeechInputDiagnostics {
    override fun info(message: String) { Log.i(TAG, message) }
    override fun warning(message: String) { Log.w(TAG, message) }
    override fun error(message: String) { Log.e(TAG, message) }
    override fun debug(message: String) { Log.d(TAG, message) }

    private const val TAG = "VoiceInteraction"
}

internal class AndroidSpeechInputDispatcher : SpeechInputDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    override fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }
}

/** Android's installed recognizer owns the microphone for this push-to-talk session. */
internal class PlatformSpeechInput(
    private val engineFactory: SpeechRecognitionEngineFactory,
    private val dispatcher: SpeechInputDispatcher,
    private val diagnostics: SpeechInputDiagnostics = AndroidSpeechInputDiagnostics,
    private val offlinePreference: (VoiceLanguage) -> Boolean = { VoiceConfig.preferOfflineSpeechRecognition }
) : SpeechInput {
    constructor(context: Context) : this(
        AndroidSpeechRecognitionEngineFactory(context.applicationContext),
        AndroidSpeechInputDispatcher()
    )

    private data class Request(
        val sessionId: Long,
        val language: VoiceLanguage,
        val callback: SpeechInput.Callback,
        var attemptCount: Int = 0,
        var preferOffline: Boolean = false,
        var speechDetected: Boolean = false
    )

    private data class Attempt(
        val request: Request,
        val number: Int,
        val engine: SpeechRecognitionEngine
    )

    @Volatile private var closed = false
    private var nextRequestId = 0L
    private var activeRequest: Request? = null
    private var activeAttempt: Attempt? = null

    override fun startListening(sessionId: Long, language: VoiceLanguage, callback: SpeechInput.Callback) {
        dispatcher.dispatch { startListeningOnMain(sessionId, language, callback) }
    }

    private fun startListeningOnMain(sessionId: Long, language: VoiceLanguage, callback: SpeechInput.Callback) {
        if (closed) {
            callback.onError(sessionId, "SES TANIMA KAPALI")
            return
        }

        val active = activeRequest
        if (active?.sessionId == sessionId) {
            diagnostics.warning("Tekrarlanan dinleme isteği yok sayıldı: oturum=$sessionId")
            return
        }
        if (active != null) cancelActive("yeni_oturum")

        val request = Request(
            sessionId = sessionId,
            language = language,
            callback = callback,
            preferOffline = offlinePreference(language)
        )
        activeRequest = request
        nextRequestId += 1
        diagnostics.info("Tanıma oturumu oluşturuldu: oturum=$sessionId, istek=$nextRequestId, dil=${language.tag}")
        startAttempt(request)
    }

    private fun startAttempt(request: Request) {
        if (closed || activeRequest !== request) return
        request.attemptCount += 1
        val number = request.attemptCount
        val engine = try {
            if (!engineFactory.isAvailable()) {
                failRequest(request, null, "BU CİHAZDA KONUŞMA TANIMA SERVİSİ BULUNAMADI")
                return
            }
            engineFactory.create()
        } catch (error: SecurityException) {
            diagnostics.error("Tanıyıcı oluşturulamadı: oturum=${request.sessionId}, izin reddedildi")
            failRequest(request, null, "MİKROFON İZNİ GEREKLİ")
            return
        } catch (error: RuntimeException) {
            diagnostics.error("Tanıyıcı oluşturulamadı: oturum=${request.sessionId}, tür=${error.javaClass.simpleName}")
            failRequest(request, null, "KONUŞMA TANIMA SERVİSİ BAŞLATILAMADI")
            return
        }

        val attempt = Attempt(request, number, engine)
        activeAttempt = attempt
        diagnostics.info(
            "Tanıyıcı oluşturuldu: oturum=${request.sessionId}, deneme=$number, çevrimdışıTercihi=${request.preferOffline}"
        )

        try {
            engine.startListening(request.language, request.preferOffline, object : SpeechRecognitionEngine.Listener {
                override fun onReadyForSpeech() = dispatchForAttempt(attempt) {
                    diagnostics.info("Konuşmaya hazır: oturum=${request.sessionId}, deneme=$number")
                }

                override fun onBeginningOfSpeech() = dispatchForAttempt(attempt) {
                    request.speechDetected = true
                    diagnostics.info("Konuşma algılandı: oturum=${request.sessionId}, deneme=$number")
                    request.callback.onSpeechStarted(request.sessionId)
                }

                override fun onEndOfSpeech() = dispatchForAttempt(attempt) {
                    diagnostics.info("Konuşma sonu algılandı: oturum=${request.sessionId}, deneme=$number")
                    request.callback.onSpeechEnded(request.sessionId)
                }

                override fun onResults(transcript: String) = dispatchForAttempt(attempt) {
                    val normalized = transcript.trim()
                    if (normalized.isEmpty()) {
                        diagnostics.warning("Boş tanıma sonucu: oturum=${request.sessionId}, deneme=$number")
                    } else {
                        diagnostics.info("Tanıma başarılı: oturum=${request.sessionId}, deneme=$number, karakter=${normalized.length}")
                    }
                    completeRequest(request, attempt)
                    request.callback.onTranscriptReady(request.sessionId, normalized)
                }

                override fun onError(errorCode: Int) = dispatchForAttempt(attempt) {
                    handleRecognitionError(attempt, errorCode)
                }
            })
            diagnostics.info("startListening çağrıldı: oturum=${request.sessionId}, deneme=$number")
        } catch (error: SecurityException) {
            diagnostics.error("startListening izin hatası: oturum=${request.sessionId}, deneme=$number")
            failRequest(request, attempt, "MİKROFON İZNİ GEREKLİ")
        } catch (error: RuntimeException) {
            diagnostics.error(
                "startListening başarısız: oturum=${request.sessionId}, deneme=$number, tür=${error.javaClass.simpleName}"
            )
            failRequest(request, attempt, "KONUŞMA TANIMA SERVİSİ BAŞLATILAMADI")
        }
    }

    private fun handleRecognitionError(attempt: Attempt, errorCode: Int) {
        val request = attempt.request
        val error = SpeechRecognitionErrors.forCode(errorCode)
        diagnostics.error(
            "Tanıma hatası: oturum=${request.sessionId}, deneme=${attempt.number}, kod=$errorCode, açıklama=${error.description}"
        )

        if (SpeechRecognitionErrors.shouldRetryOnce(
                errorCode = errorCode,
                speechDetected = request.speechDetected,
                previousRetries = request.attemptCount - 1
            )
        ) {
            cleanupAttempt(attempt, cancelService = false)
            val wasOfflineOnly = request.preferOffline
            request.preferOffline = false
            diagnostics.warning(
                "Tek kurtarma denemesi: oturum=${request.sessionId}, nedeni=${error.description}, offlineOnlyÖnceki=$wasOfflineOnly, ağKullanımıAçık=true"
            )
            startAttempt(request)
            return
        }

        completeRequest(request, attempt)
        request.callback.onError(request.sessionId, error.message)
    }

    private fun dispatchForAttempt(attempt: Attempt, action: () -> Unit) {
        dispatcher.dispatch {
            if (closed || activeRequest !== attempt.request || activeAttempt !== attempt) {
                diagnostics.debug("Eski tanıyıcı geri çağrısı yok sayıldı: oturum=${attempt.request.sessionId}, deneme=${attempt.number}")
                return@dispatch
            }
            action()
        }
    }

    private fun completeRequest(request: Request, attempt: Attempt?) {
        if (activeRequest !== request) return
        activeRequest = null
        attempt?.let { cleanupAttempt(it, cancelService = false) }
        diagnostics.info("Tanıma oturumu tamamlandı: oturum=${request.sessionId}")
    }

    private fun failRequest(request: Request, attempt: Attempt?, message: String) {
        if (activeRequest !== request) return
        activeRequest = null
        attempt?.let { cleanupAttempt(it, cancelService = true) }
        diagnostics.error("Tanıma oturumu başarısız: oturum=${request.sessionId}")
        request.callback.onError(request.sessionId, message)
    }

    private fun cleanupAttempt(attempt: Attempt, cancelService: Boolean) {
        if (activeAttempt !== attempt) return
        activeAttempt = null
        if (cancelService) {
            try {
                attempt.engine.cancel()
            } catch (_: RuntimeException) {
                diagnostics.warning("Tanıyıcı iptali başarısız: oturum=${attempt.request.sessionId}, deneme=${attempt.number}")
            }
        }
        try {
            attempt.engine.destroy()
        } catch (_: RuntimeException) {
            diagnostics.warning("Tanıyıcı kapatma başarısız: oturum=${attempt.request.sessionId}, deneme=${attempt.number}")
        }
        diagnostics.info("Tanıyıcı kaynağı temizlendi: oturum=${attempt.request.sessionId}, deneme=${attempt.number}")
    }

    private fun cancelActive(reason: String) {
        val request = activeRequest ?: return
        val attempt = activeAttempt
        activeRequest = null
        if (attempt != null) cleanupAttempt(attempt, cancelService = true)
        diagnostics.info("Tanıma oturumu iptal edildi: oturum=${request.sessionId}, neden=$reason")
    }

    override fun cancel() {
        dispatcher.dispatch { cancelActive("kullanıcı_veya_lifecycle") }
    }

    override fun close() {
        closed = true
        dispatcher.dispatch { cancelActive("kapatılıyor") }
    }

}

/** Android API adapter. Every SpeechRecognizer operation is dispatched to the main thread. */
internal class AndroidSpeechRecognitionEngineFactory(context: Context) : SpeechRecognitionEngineFactory {
    private val appContext = context.applicationContext

    override fun isAvailable(): Boolean =
        android.speech.SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun create(): SpeechRecognitionEngine = AndroidSpeechRecognitionEngine(
        android.speech.SpeechRecognizer.createSpeechRecognizer(appContext)
    )
}

private class AndroidSpeechRecognitionEngine(
    private val recognizer: android.speech.SpeechRecognizer
) : SpeechRecognitionEngine {
    override fun startListening(
        language: VoiceLanguage,
        preferOffline: Boolean,
        listener: SpeechRecognitionEngine.Listener
    ) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReadyForSpeech()
            override fun onBeginningOfSpeech() = listener.onBeginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.onEndOfSpeech()
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                val transcript = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                listener.onResults(transcript)
            }

            override fun onError(error: Int) = listener.onError(error)
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}

internal data class SpeechRecognitionErrorInfo(
    val description: String,
    val message: String
)

internal object SpeechRecognitionErrors {
    fun forCode(code: Int): SpeechRecognitionErrorInfo = when (code) {
        android.speech.SpeechRecognizer.ERROR_AUDIO -> SpeechRecognitionErrorInfo(
            "audio_input", "Mikrofon ses akışı başlatılamadı."
        )

        android.speech.SpeechRecognizer.ERROR_CLIENT -> SpeechRecognitionErrorInfo(
            "client_interrupted", "Ses tanıma oturumu kesildi."
        )

        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionErrorInfo(
            "microphone_permission", "Mikrofon izni gerekli."
        )

        android.speech.SpeechRecognizer.ERROR_NETWORK -> SpeechRecognitionErrorInfo(
            "network_unavailable", "Konuşma tanıma servisine ağ üzerinden ulaşılamadı."
        )

        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechRecognitionErrorInfo(
            "network_timeout", "Konuşma tanıma ağ zaman aşımına uğradı."
        )

        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> SpeechRecognitionErrorInfo(
            "no_match", "Konuşma anlaşılamadı."
        )

        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechRecognitionErrorInfo(
            "recognizer_busy", "Ses tanıma servisi meşgul. Birkaç saniye sonra yeniden dene."
        )

        android.speech.SpeechRecognizer.ERROR_SERVER -> SpeechRecognitionErrorInfo(
            "server_error", "Ses tanıma servisi yanıt vermedi."
        )

        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechRecognitionErrorInfo(
            "speech_timeout", "Ses algılanamadı."
        )

        SpeechRecognitionErrorCode.TOO_MANY_REQUESTS -> SpeechRecognitionErrorInfo(
            "too_many_requests", "Ses tanıma isteği sınırına ulaşıldı. Biraz sonra yeniden dene."
        )

        SpeechRecognitionErrorCode.SERVER_DISCONNECTED -> SpeechRecognitionErrorInfo(
            "server_disconnected", "Konuşma tanıma servisi bağlantısı kesildi."
        )

        SpeechRecognitionErrorCode.LANGUAGE_NOT_SUPPORTED -> SpeechRecognitionErrorInfo(
            "language_not_supported", "Bu konuşma tanıma servisi Türkçeyi desteklemiyor."
        )

        SpeechRecognitionErrorCode.LANGUAGE_UNAVAILABLE -> SpeechRecognitionErrorInfo(
            "language_unavailable", "Türkçe konuşma modeli cihazda kullanılamıyor."
        )

        else -> SpeechRecognitionErrorInfo("unknown_$code", "Konuşma tanıma hatası ($code).")
    }

    fun shouldRetryOnce(
        errorCode: Int,
        speechDetected: Boolean,
        previousRetries: Int
    ): Boolean = !speechDetected && previousRetries == 0 &&
        (errorCode == android.speech.SpeechRecognizer.ERROR_CLIENT ||
            errorCode == SpeechRecognitionErrorCode.SERVER_DISCONNECTED)
}

/** Error values introduced after API 24, kept as literals to avoid unavailable-field linkage. */
internal object SpeechRecognitionErrorCode {
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
}
