package com.companion.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Coordinates one push-to-talk session without keeping audio or network work on the UI thread. */
internal class VoiceInteractionController(
    private val robotCore: RobotCore,
    private val speechRecognizer: SpeechRecognizer,
    private val speechOutput: SpeechOutput,
    private val conversationEngine: ConversationEngine,
    private val conversationHistory: ConversationHistoryStore,
    private val languageProvider: () -> VoiceLanguage = { VoiceConfig.language }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioRecorder = AudioRecorder()
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
        Log.i(TAG, "Dinleme başladı: oturum=$sessionId")

        val started = audioRecorder.start(object : AudioRecorder.Callback {
            override fun onRecordingComplete(recording: AudioRecording) {
                mainHandler.post {
                    if (closed || !robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)) {
                        recording.close()
                        return@post
                    }
                    Log.i(TAG, "Ses kaydı tamamlandı: oturum=$sessionId")
                    robotCore.dispatch(RobotEvent.RecordingCompleted(sessionId))
                    beginRecognition(sessionId, recording)
                }
            }

            override fun onRecordingError(message: String) {
                mainHandler.post { reportFailure(sessionId, message) }
            }
        })

        if (!started) reportFailure(sessionId, "MİKROFON ŞU ANDA KULLANILIYOR")
    }

    fun cancelSession(sessionId: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelSession(sessionId) }
            return
        }
        if (activeSessionId != sessionId) return
        audioRecorder.cancel()
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
        recognitionExecutor.shutdownNow()
        speechOutput.close()
    }

    private fun beginRecognition(session: Long, recording: AudioRecording) {
        activeRecording = recording
        val language = languageProvider()

        try {
            recognitionTask = recognitionExecutor.submit {
                var reply: ConversationReply? = null
                var recognizedText: String? = null
                var failure: String? = null
                var stage = "speech_recognition"
                try {
                    val transcript = try {
                        speechRecognizer.recognize(recording, language).trim()
                    } finally {
                        recording.close()
                    }
                    if (transcript.isEmpty()) {
                        if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                            mainHandler.post {
                                if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                                    robotCore.dispatch(RobotEvent.TranscriptReady(session, hasText = false))
                                    activeRecording = null
                                    recognitionTask = null
                                    activeSessionId = null
                                }
                            }
                        }
                    } else if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                        recognizedText = transcript
                        stage = "conversation"
                        Log.i(TAG, "Ses tanıma tamamlandı; sohbet isteği başlıyor: oturum=$session")
                        mainHandler.post {
                            if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                                robotCore.dispatch(RobotEvent.TranscriptReady(session, hasText = true))
                            }
                        }
                        val priorMessages = conversationHistory.recentMessages()
                        val generatedReply = conversationEngine.respond(transcript, language, priorMessages)
                        if (robotCore.isCurrent(session, RobotActivityState.THINKING) && generatedReply.text.isNotBlank()) {
                            reply = generatedReply
                        } else if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                            failure = "BULUT ASİSTANI BOŞ YANIT VERDİ"
                        }
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    if (robotCore.snapshot.sessionId == session && robotCore.snapshot.activity == RobotActivityState.THINKING) {
                        Log.w(TAG, "Sesli işlem başarısız: oturum=$session, aşama=$stage, tür=${error.javaClass.simpleName}, neden=${error.cause?.javaClass?.simpleName ?: "yok"}")
                    }
                    val message = when (error) {
                        is SpeechRecognitionFailure, is ConversationFailure -> error.message ?: "KONUŞMA TAMAMLANAMADI"
                        else -> "KONUŞMA TAMAMLANAMADI"
                    }
                    failure = message
                }

                mainHandler.post {
                    if (!robotCore.isCurrent(session, RobotActivityState.THINKING)) return@post
                    activeRecording = null
                    recognitionTask = null
                    val generatedReply = reply
                    if (generatedReply == null) {
                        if (failure != null) reportFailure(session, failure ?: "KONUŞMA TAMAMLANAMADI")
                    } else {
                        recognizedText?.let { conversationHistory.appendTurn(it, generatedReply.text) }
                        robotCore.dispatch(RobotEvent.ConversationReady(session, hasReply = generatedReply.text.isNotBlank()))
                        if (robotCore.isCurrent(session, RobotActivityState.SPEAKING)) {
                            speakResponse(session, generatedReply.text, language)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            recording.close()
            activeRecording = null
            reportFailure(session, "SES TANIMA BAŞLATILAMADI")
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
        audioRecorder.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeSessionId = null
        speechOutput.stop()
        robotCore.dispatch(RobotEvent.SessionFailed(session, message))
    }

    private fun cancelSessionOnClose(sessionId: Long) {
        audioRecorder.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeSessionId = null
        conversationEngine.cancelCurrentRequest()
        speechOutput.stop()
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
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
