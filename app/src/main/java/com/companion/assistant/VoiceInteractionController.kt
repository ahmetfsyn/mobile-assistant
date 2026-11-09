package com.companion.assistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.UUID

/** Coordinates push-to-talk input, recognition and a response without blocking the UI thread. */
internal class VoiceInteractionController(
    private val robotCore: RobotCore,
    private val speechInput: SpeechInput,
    private val recordedSpeechRecognizer: SpeechRecognizer?,
    private val speechOutput: SpeechOutput,
    private val conversationEngine: ConversationEngine,
    private val conversationHistory: ConversationHistoryStore,
    private val languageProvider: () -> VoiceLanguage = { VoiceConfig.language },
    private val onCaptionChanged: (String?) -> Unit = {},
    private val personalityProfile: PersonalityProfile = PersonalityProfile.DEFAULT,
    private val onFaceCueChanged: (FaceCue) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognitionExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CompanionSpeechRecognition").apply { isDaemon = true }
    }

    @Volatile private var closed = false
    @Volatile private var activeSessionId: Long? = null
    @Volatile private var activeInteractionId: String? = null
    private var recognitionTask: Future<*>? = null
    private var activeRecording: AudioRecording? = null
    private val emotionReducer = EmotionReducer()
    private var emotionState = EmotionState.CALM
    private val emotionDecayRunnable = Runnable {
        if (!closed) {
            emotionState = emotionReducer.decay(emotionState, SystemClock.elapsedRealtime())
            onFaceCueChanged(FaceCueMapper.map(emotionState))
        }
    }

    fun startListening(sessionId: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListening(sessionId) }
            return
        }
        if (closed || !robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)) return
        activeSessionId = sessionId
        mainHandler.removeCallbacks(emotionDecayRunnable)
        val interactionId = UUID.randomUUID().toString()
        activeInteractionId = interactionId
        applyEmotionEvent(EmotionEvent.INTERACTION_STARTED)
        onCaptionChanged(null)
        val language = languageProvider()
        Log.i(TAG, "recognition_session_started interactionId=$interactionId session=$sessionId language=${language.tag}")

        try {
            speechInput.startListening(sessionId, language, object : SpeechInput.Callback {
                override fun onRecordingReady(sessionId: Long, recording: AudioRecording) {
                    dispatchOnMain { onRecordingReady(sessionId, language, interactionId, recording) }
                }

                override fun onTranscriptReady(sessionId: Long, transcript: String) {
                    dispatchOnMain { onTranscriptReady(sessionId, language, interactionId, transcript) }
                }

                override fun onError(sessionId: Long, message: String) {
                    dispatchOnMain {
                        Log.w(TAG, "recognition_failed interactionId=$interactionId session=$sessionId")
                        reportFailure(sessionId, message)
                    }
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
        Log.i(TAG, "interaction_cancelled interactionId=${activeInteractionId ?: "unknown"} session=$sessionId")
        speechInput.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        conversationEngine.cancelCurrentRequest()
        speechOutput.stop()
        activeSessionId = null
        activeInteractionId = null
        onCaptionChanged(null)
    }

    fun close() {
        if (closed) return
        closed = true
        mainHandler.removeCallbacks(emotionDecayRunnable)
        activeSessionId?.let(::cancelSessionOnClose)
        speechInput.close()
        recognitionExecutor.shutdownNow()
        speechOutput.close()
        onCaptionChanged(null)
    }

    private fun onRecordingReady(
        session: Long,
        language: VoiceLanguage,
        interactionId: String,
        recording: AudioRecording
    ) {
        if (closed || activeInteractionId != interactionId ||
            !robotCore.isCurrent(session, RobotActivityState.LISTENING)
        ) {
            recording.close()
            return
        }
        Log.i(TAG, "recording_completed interactionId=$interactionId session=$session")
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
                    else onTranscriptReady(session, language, interactionId, transcript.orEmpty())
                }
            }
        } catch (_: Exception) {
            recording.close()
            activeRecording = null
            reportFailure(session, "SES TANIMA BAŞLATILAMADI")
        }
    }

    private fun onTranscriptReady(
        session: Long,
        language: VoiceLanguage,
        interactionId: String,
        rawTranscript: String
    ) {
        if (closed || activeInteractionId != interactionId) {
            Log.i(TAG, "stale_recognition_result_ignored interactionId=$interactionId session=$session")
            return
        }
        if (!robotCore.isCurrent(session, RobotActivityState.LISTENING) &&
            !robotCore.isCurrent(session, RobotActivityState.THINKING)
        ) return

        val transcript = rawTranscript.trim()
        Log.i(
            TAG,
            "recognition_result_received interactionId=$interactionId session=$session chars=${transcript.length} " +
                "fingerprint=${VoiceDiagnostics.fingerprint(interactionId, transcript)} " +
                "preview=${VoiceDiagnostics.redactedPreview(transcript)}"
        )
        robotCore.dispatch(RobotEvent.TranscriptReady(session, hasText = transcript.isNotEmpty()))
        if (transcript.isEmpty()) {
            activeSessionId = null
            activeInteractionId = null
            onCaptionChanged(null)
            Log.w(TAG, "empty_transcript_not_submitted interactionId=$interactionId session=$session")
            return
        }
        if (!robotCore.isCurrent(session, RobotActivityState.THINKING)) return
        applyEmotionEvent(EmotionEvent.TRANSCRIPT_RECEIVED)
        onCaptionChanged(transcript)

        Log.i(
            TAG,
            "conversation_input_ready interactionId=$interactionId session=$session " +
                "chars=${transcript.length} fingerprint=${VoiceDiagnostics.fingerprint(interactionId, transcript)} " +
                "preview=${VoiceDiagnostics.redactedPreview(transcript)}"
        )
        try {
            recognitionTask = recognitionExecutor.submit {
                var reply: ConversationReply? = null
                var failure: String? = null
                try {
                    if (closed || activeInteractionId != interactionId ||
                        !robotCore.isCurrent(session, RobotActivityState.THINKING)
                    ) return@submit
                    val priorMessages = conversationHistory.recentMessages()
                    Log.i(
                        TAG,
                        "conversation_engine_call interactionId=$interactionId session=$session " +
                            "messageFingerprint=${VoiceDiagnostics.fingerprint(interactionId, transcript)} " +
                            "historyCount=${priorMessages.size}"
                    )
                    val generatedReply = conversationEngine.respond(transcript, language, priorMessages, interactionId)
                    if (generatedReply.text.isNotBlank()) reply = generatedReply
                    else failure = "BULUT ASİSTANI BOŞ YANIT VERDİ"
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                        Log.w(
                            TAG,
                            "conversation_failed interactionId=$interactionId session=$session " +
                                "type=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName ?: "none"}"
                        )
                    }
                    failure = when (error) {
                        is ConversationFailure -> error.message ?: "KONUŞMA TAMAMLANAMADI"
                        else -> "KONUŞMA TAMAMLANAMADI"
                    }
                }

                mainHandler.post {
                    if (closed || activeInteractionId != interactionId ||
                        !robotCore.isCurrent(session, RobotActivityState.THINKING)
                    ) {
                        Log.i(TAG, "stale_conversation_response_ignored interactionId=$interactionId session=$session")
                        return@post
                    }
                    recognitionTask = null
                    val generatedReply = reply
                    if (generatedReply == null) {
                        reportFailure(session, failure ?: "KONUŞMA TAMAMLANAMADI")
                    } else {
                        applyEmotionEvent(EmotionEvent.RESPONSE_RECEIVED)
                        onCaptionChanged(generatedReply.text)
                        Log.i(
                            TAG,
                            "conversation_response_ready interactionId=$interactionId session=$session " +
                                "chars=${generatedReply.text.length} " +
                                "preview=${VoiceDiagnostics.redactedPreview(generatedReply.text)}"
                        )
                        conversationHistory.appendTurn(transcript, generatedReply.text)
                        robotCore.dispatch(RobotEvent.ConversationReady(session, hasReply = true))
                        if (robotCore.isCurrent(session, RobotActivityState.SPEAKING)) {
                            speakResponse(session, interactionId, generatedReply.text, language)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            reportFailure(session, "SOHBET İSTEĞİ BAŞLATILAMADI")
        }
    }

    private fun speakResponse(session: Long, interactionId: String, response: String, language: VoiceLanguage) {
        if (closed || activeInteractionId != interactionId ||
            !robotCore.isCurrent(session, RobotActivityState.SPEAKING)
        ) return

        speechOutput.speak(
            response,
            language,
            personalityProfile.voiceDeliveryFor(emotionState),
            object : SpeechOutput.Callback {
            override fun onStarted() {
                Log.i(
                    TAG,
                    "tts_playback_started interactionId=$interactionId session=$session chars=${response.length} " +
                        "preview=${VoiceDiagnostics.redactedPreview(response)}"
                )
            }

            override fun onCompleted() = dispatchOnMain {
                if (closed || activeSessionId != session || activeInteractionId != interactionId) {
                    return@dispatchOnMain
                }
                if (activeSessionId == session && activeInteractionId == interactionId) {
                    activeSessionId = null
                    activeInteractionId = null
                }
                applyEmotionEvent(EmotionEvent.INTERACTION_FINISHED, scheduleDecay = true)
                Log.i(TAG, "tts_playback_completed interactionId=$interactionId session=$session")
                robotCore.dispatch(RobotEvent.SpeechCompleted(session))
            }

            override fun onError(message: String) = dispatchOnMain {
                Log.w(TAG, "tts_playback_failed interactionId=$interactionId session=$session")
                reportFailure(session, message)
            }
        })
    }

    private fun reportFailure(session: Long, message: String) {
        if (closed || robotCore.snapshot.sessionId != session) return
        if (robotCore.snapshot.activity !in VOICE_STATES) return
        Log.w(TAG, "interaction_failed interactionId=${activeInteractionId ?: "unknown"} session=$session")
        applyEmotionEvent(EmotionEvent.INTERACTION_FAILED, scheduleDecay = true)
        speechInput.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeSessionId = null
        activeInteractionId = null
        speechOutput.stop()
        onCaptionChanged(null)
        robotCore.dispatch(RobotEvent.SessionFailed(session, message))
    }

    private fun cancelSessionOnClose(sessionId: Long) {
        speechInput.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeSessionId = null
        activeInteractionId = null
        conversationEngine.cancelCurrentRequest()
        speechOutput.stop()
        onCaptionChanged(null)
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun applyEmotionEvent(event: EmotionEvent, scheduleDecay: Boolean = false) {
        emotionState = emotionReducer.reduce(emotionState, event, SystemClock.elapsedRealtime())
        onFaceCueChanged(FaceCueMapper.map(emotionState))
        mainHandler.removeCallbacks(emotionDecayRunnable)
        if (scheduleDecay && !closed) {
            mainHandler.postDelayed(emotionDecayRunnable, EMOTION_DECAY_REFRESH_MS)
        }
    }

    private companion object {
        const val TAG = "VoiceInteraction"
        const val EMOTION_DECAY_REFRESH_MS = 22_000L
        val VOICE_STATES = setOf(
            RobotActivityState.LISTENING,
            RobotActivityState.THINKING,
            RobotActivityState.SPEAKING
        )
    }
}
