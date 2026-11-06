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
    private val languageProvider: () -> VoiceLanguage = { VoiceConfig.language }
) {
    private enum class State { IDLE, LISTENING, PROCESSING, SPEAKING, CLOSED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioRecorder = AudioRecorder()
    private val recognitionExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CompanionSpeechRecognition").apply { isDaemon = true }
    }

    private var state = State.IDLE
    private var sessionId = 0L
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
                var transcript: String? = null
                var failure: String? = null
                try {
                    transcript = speechRecognizer.recognize(recording, language).trim()
                    if (transcript.isNullOrEmpty()) failure = "KONUŞMA ANLAŞILAMADI"
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    failure = (error as? SpeechRecognitionFailure)?.message ?: "SES TANIMA BAŞARISIZ"
                } finally {
                    recording.close()
                }

                mainHandler.post {
                    if (!isCurrent(session, State.PROCESSING)) return@post
                    activeRecording = null
                    recognitionTask = null
                    val recognizedText = transcript
                    if (recognizedText.isNullOrEmpty()) {
                        fail(session, failure ?: "KONUŞMA ANLAŞILAMADI")
                    } else {
                        respond(session, recognizedText, language)
                    }
                }
            }
        } catch (_: Exception) {
            recording.close()
            activeRecording = null
            fail(session, "SES TANIMA BAŞLATILAMADI")
        }
    }

    private fun respond(session: Long, recognizedText: String, language: VoiceLanguage) {
        if (!isCurrent(session, State.PROCESSING)) return
        Log.i(TAG, "Tanınan konuşma: $recognizedText")

        val response = VoiceConfig.testResponse(language)
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
