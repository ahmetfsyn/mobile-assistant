package com.companion.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Coordinates one push-to-talk session without keeping audio or network work on the UI thread. */
internal class VoiceInteractionController(
    private val faceView: FaceView,
    private val speechRecognizer: SpeechRecognizer,
    private val speechOutput: SpeechOutput,
    private val conversationEngine: ConversationEngine,
    private val conversationHistory: ConversationHistoryStore,
    private val languageProvider: () -> VoiceLanguage = { VoiceConfig.language }
) {
    private enum class State { IDLE, LISTENING, PROCESSING, SPEAKING, CLOSED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioRecorder = AudioRecorder()
    private val recognitionExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CompanionSpeechRecognition").apply { isDaemon = true }
    }

    @Volatile private var state = State.IDLE
    @Volatile private var sessionId = 0L
    private var recognitionTask: Future<*>? = null
    private var activeRecording: AudioRecording? = null

    fun toggleListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { toggleListening() }
            return
        }
        if (state == State.CLOSED) return

        when (state) {
            State.LISTENING -> cancelSession(showIdle = true)
            State.PROCESSING, State.SPEAKING -> {
                cancelSession(showIdle = false)
                startListening()
            }
            State.IDLE -> startListening()
            State.CLOSED -> Unit
        }
    }

    fun onHostPaused() {
        if (state != State.CLOSED && state != State.IDLE) cancelSession(showIdle = true)
    }

    fun close() {
        if (state == State.CLOSED) return
        cancelSession(showIdle = false)
        state = State.CLOSED
        recognitionExecutor.shutdownNow()
        speechOutput.close()
    }

    private fun startListening() {
        if (state == State.CLOSED) return
        val session = ++sessionId
        state = State.LISTENING
        faceView.showVoiceListening()

        val started = audioRecorder.start(object : AudioRecorder.Callback {
            override fun onRecordingComplete(recording: AudioRecording) {
                mainHandler.post {
                    if (!isCurrent(session, State.LISTENING)) {
                        recording.close()
                        return@post
                    }
                    beginRecognition(session, recording)
                }
            }

            override fun onRecordingError(message: String) {
                mainHandler.post {
                    if (isCurrent(session, State.LISTENING)) fail(session, message)
                }
            }
        })

        if (!started) fail(session, "MİKROFON ŞU ANDA KULLANILIYOR")
    }

    private fun beginRecognition(session: Long, recording: AudioRecording) {
        state = State.PROCESSING
        activeRecording = recording
        faceView.showVoiceProcessing()
        val language = languageProvider()

        try {
            recognitionTask = recognitionExecutor.submit {
                var reply: ConversationReply? = null
                var recognizedText: String? = null
                var failure: String? = null
                try {
                    val transcript = try {
                        speechRecognizer.recognize(recording, language).trim()
                    } finally {
                        recording.close()
                    }
                    if (transcript.isEmpty()) {
                        failure = "KONUŞMA ANLAŞILAMADI"
                    } else if (isCurrent(session, State.PROCESSING)) {
                        recognizedText = transcript
                        val priorMessages = conversationHistory.recentMessages()
                        val generatedReply = conversationEngine.respond(transcript, language, priorMessages)
                        if (isCurrent(session, State.PROCESSING) && generatedReply.text.isNotBlank()) {
                            reply = generatedReply
                        } else if (isCurrent(session, State.PROCESSING)) {
                            failure = "BULUT ASİSTANI BOŞ YANIT VERDİ"
                        }
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    failure = when (error) {
                        is SpeechRecognitionFailure, is ConversationFailure -> error.message ?: "KONUŞMA TAMAMLANAMADI"
                        else -> "KONUŞMA TAMAMLANAMADI"
                    }
                }

                mainHandler.post {
                    if (!isCurrent(session, State.PROCESSING)) return@post
                    activeRecording = null
                    recognitionTask = null
                    val generatedReply = reply
                    if (generatedReply == null) {
                        if (failure != null) fail(session, failure ?: "KONUŞMA TAMAMLANAMADI")
                    } else {
                        recognizedText?.let { conversationHistory.appendTurn(it, generatedReply.text) }
                        speakResponse(session, generatedReply.text, language)
                    }
                }
            }
        } catch (_: Exception) {
            recording.close()
            activeRecording = null
            fail(session, "SES TANIMA BAŞLATILAMADI")
        }
    }

    private fun speakResponse(session: Long, response: String, language: VoiceLanguage) {
        if (!isCurrent(session, State.PROCESSING)) return
        Log.i(TAG, "Yanıt hazır (${response.length} karakter).")

        state = State.SPEAKING
        faceView.showVoiceSpeaking()
        speechOutput.speak(response, language, object : SpeechOutput.Callback {
            override fun onStarted() = Unit

            override fun onCompleted() {
                if (!isCurrent(session, State.SPEAKING)) return
                state = State.IDLE
                faceView.finishVoiceInteraction()
            }

            override fun onError(message: String) {
                if (isCurrent(session, State.SPEAKING)) fail(session, message)
            }
        })
    }

    private fun fail(session: Long, message: String) {
        if (session != sessionId || state == State.CLOSED) return
        audioRecorder.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        speechOutput.stop()
        state = State.IDLE
        faceView.showVoiceError(message)
    }

    private fun cancelSession(showIdle: Boolean) {
        if (state == State.PROCESSING) conversationEngine.cancelCurrentRequest()
        sessionId++
        audioRecorder.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        speechOutput.stop()
        if (state != State.CLOSED) state = State.IDLE
        if (showIdle) faceView.finishVoiceInteraction()
    }

    private fun isCurrent(session: Long, expectedState: State): Boolean =
        session == sessionId && state == expectedState

    private companion object {
        const val TAG = "VoiceInteraction"
    }
}
