package com.companion.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Coordinates push-to-talk input, recognition and a response without blocking the UI thread. */
internal class VoiceInteractionController(
    private val robotCore: RobotCore,
    private val speechInput: SpeechInput,
    private val recordedSpeechRecognizer: SpeechRecognizer?,
    private val speechOutput: SpeechOutput,
    private val conversationEngine: ConversationEngine,
    private val conversationHistory: ConversationHistoryStore,
    private val languageProvider: () -> VoiceLanguage = { VoiceConfig.language }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognitionExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CompanionSpeechRecognition").apply { isDaemon = true }
    }

    @Volatile private var closed = false
    @Volatile private var activeSessionId: Long? = null
    private var recognitionTask: Future<*>? = null
    private var activeRecording: AudioRecording? = null

    fun startListening(sessionId: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListening(sessionId) }
            return
        }
        if (closed || !robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)) return
        activeSessionId = sessionId
        val language = languageProvider()
        Log.i(TAG, "Dinleme başladı: oturum=$sessionId, dil=${language.tag}")

        try {
            speechInput.startListening(sessionId, language, object : SpeechInput.Callback {
                override fun onRecordingReady(sessionId: Long, recording: AudioRecording) {
                    dispatchOnMain { onRecordingReady(sessionId, language, recording) }
                }

                override fun onTranscriptReady(sessionId: Long, transcript: String) {
                    dispatchOnMain { onTranscriptReady(sessionId, language, transcript) }
                }

                override fun onError(sessionId: Long, message: String) {
                    dispatchOnMain { reportFailure(sessionId, message) }
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "Ses tanıma başlatılamadı: tür=${error.javaClass.simpleName}")
            reportFailure(sessionId, "KONUŞMA TANIMA SERVİSİ BAŞLATILAMADI")
        }
    }

    fun cancelSession(sessionId: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelSession(sessionId) }
            return
        }
        if (activeSessionId != sessionId) return
        speechInput.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        conversationEngine.cancelCurrentRequest()
        speechOutput.stop()
        activeSessionId = null
    }

    fun close() {
        if (closed) return
        closed = true
        activeSessionId?.let(::cancelSessionOnClose)
        speechInput.close()
        recognitionExecutor.shutdownNow()
        speechOutput.close()
    }

    private fun onRecordingReady(session: Long, language: VoiceLanguage, recording: AudioRecording) {
        if (closed || !robotCore.isCurrent(session, RobotActivityState.LISTENING)) {
            recording.close()
            return
        }
        Log.i(TAG, "Ses kaydı tamamlandı: oturum=$session")
        robotCore.dispatch(RobotEvent.RecordingCompleted(session))
        val recognizer = recordedSpeechRecognizer
        if (recognizer == null) {
            recording.close()
            reportFailure(session, "KONUŞMA TANIMA YAPILANDIRMASI HATALI")
            return
        }

        activeRecording = recording
        try {
            recognitionTask = recognitionExecutor.submit {
                var transcript: String? = null
                var failure: String? = null
                try {
                    transcript = recognizer.recognize(recording, language).trim()
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    failure = error.message ?: "SES TANIMA TAMAMLANAMADI"
                } finally {
                    recording.close()
                }

                mainHandler.post {
                    if (closed || !robotCore.isCurrent(session, RobotActivityState.THINKING)) return@post
                    activeRecording = null
                    recognitionTask = null
                    val error = failure
                    if (error != null) reportFailure(session, error)
                    else onTranscriptReady(session, language, transcript.orEmpty())
                }
            }
        } catch (_: Exception) {
            recording.close()
            activeRecording = null
            reportFailure(session, "SES TANIMA BAŞLATILAMADI")
        }
    }

    private fun onTranscriptReady(session: Long, language: VoiceLanguage, rawTranscript: String) {
        if (closed) return
        if (!robotCore.isCurrent(session, RobotActivityState.LISTENING) &&
            !robotCore.isCurrent(session, RobotActivityState.THINKING)
        ) return

        val transcript = rawTranscript.trim()
        robotCore.dispatch(RobotEvent.TranscriptReady(session, hasText = transcript.isNotEmpty()))
        if (transcript.isEmpty()) {
            activeSessionId = null
            return
        }
        if (!robotCore.isCurrent(session, RobotActivityState.THINKING)) return

        Log.i(TAG, "Ses tanıma tamamlandı: oturum=$session, karakter=${transcript.length}")
        try {
            recognitionTask = recognitionExecutor.submit {
                var reply: ConversationReply? = null
                var failure: String? = null
                try {
                    val priorMessages = conversationHistory.recentMessages()
                    val generatedReply = conversationEngine.respond(transcript, language, priorMessages)
                    if (generatedReply.text.isNotBlank()) reply = generatedReply
                    else failure = "BULUT ASİSTANI BOŞ YANIT VERDİ"
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                        Log.w(
                            TAG,
                            "Sohbet isteği başarısız: oturum=$session, tür=${error.javaClass.simpleName}, neden=${error.cause?.javaClass?.simpleName ?: "yok"}"
                        )
                    }
                    failure = when (error) {
                        is ConversationFailure -> error.message ?: "KONUŞMA TAMAMLANAMADI"
                        else -> "KONUŞMA TAMAMLANAMADI"
                    }
                }

                mainHandler.post {
                    if (closed || !robotCore.isCurrent(session, RobotActivityState.THINKING)) return@post
                    recognitionTask = null
                    val generatedReply = reply
                    if (generatedReply == null) {
                        reportFailure(session, failure ?: "KONUŞMA TAMAMLANAMADI")
                    } else {
                        conversationHistory.appendTurn(transcript, generatedReply.text)
                        robotCore.dispatch(RobotEvent.ConversationReady(session, hasReply = true))
                        if (robotCore.isCurrent(session, RobotActivityState.SPEAKING)) {
                            speakResponse(session, generatedReply.text, language)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            reportFailure(session, "SOHBET İSTEĞİ BAŞLATILAMADI")
        }
    }

    private fun speakResponse(session: Long, response: String, language: VoiceLanguage) {
        if (closed || !robotCore.isCurrent(session, RobotActivityState.SPEAKING)) return
        Log.i(TAG, "Yanıt hazır (${response.length} karakter).")

        speechOutput.speak(response, language, object : SpeechOutput.Callback {
            override fun onStarted() = Unit

            override fun onCompleted() = dispatchOnMain {
                if (activeSessionId == session) activeSessionId = null
                robotCore.dispatch(RobotEvent.SpeechCompleted(session))
            }

            override fun onError(message: String) = dispatchOnMain { reportFailure(session, message) }
        })
    }

    private fun reportFailure(session: Long, message: String) {
        if (closed || robotCore.snapshot.sessionId != session) return
        if (robotCore.snapshot.activity !in VOICE_STATES) return
        speechInput.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeSessionId = null
        speechOutput.stop()
        robotCore.dispatch(RobotEvent.SessionFailed(session, message))
    }

    private fun cancelSessionOnClose(sessionId: Long) {
        speechInput.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeSessionId = null
        conversationEngine.cancelCurrentRequest()
        speechOutput.stop()
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "VoiceInteraction"
        val VOICE_STATES = setOf(
            RobotActivityState.LISTENING,
            RobotActivityState.THINKING,
            RobotActivityState.SPEAKING
        )
    }
}
