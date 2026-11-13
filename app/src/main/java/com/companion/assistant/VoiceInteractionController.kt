package com.companion.assistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.UUID
import java.util.Locale

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
    private val onFaceCueChanged: (FaceCue) -> Unit = {},
    private val onAuthenticationRequired: () -> Unit = {},
    private val reminderCommandProcessor: ReminderCommandProcessor? = null,
    private val nativeAlarmCommandProcessor: NativeAlarmCommandProcessor? = null,
    private val localClockCommandProcessor: LocalClockCommandProcessor? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognitionExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CompanionSpeechRecognition").apply { isDaemon = true }
    }

    @Volatile private var closed = false
    @Volatile private var activeSessionId: Long? = null
    @Volatile private var activeInteractionId: String? = null
    @Volatile private var activeMicrophoneLease: MicrophoneOwnership.Lease? = null
    @Volatile private var recognitionStartedAtMs = 0L
    @Volatile private var speechEndedAtMs = 0L
    @Volatile private var requestStartedAtMs = 0L
    @Volatile private var replyReadyAtMs = 0L
    private var recognitionTask: Future<*>? = null
    private var activeRecording: AudioRecording? = null
    private var noCommandTimeout: Runnable? = null
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
        recognitionStartedAtMs = SystemClock.elapsedRealtime()
        speechEndedAtMs = 0L
        requestStartedAtMs = 0L
        replyReadyAtMs = 0L
        cancelNoCommandTimeout()
        mainHandler.removeCallbacks(emotionDecayRunnable)
        val interactionId = UUID.randomUUID().toString()
        activeInteractionId = interactionId
        applyEmotionEvent(EmotionEvent.INTERACTION_STARTED)
        onCaptionChanged(null)
        val language = languageProvider()
        Log.i(TAG, "recognition_session_started interactionId=$interactionId session=$sessionId language=${language.tag}")

        val microphoneLease = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.PUSH_TO_TALK)
        if (microphoneLease == null) {
            reportFailure(sessionId, "MIKROFON BAŞKA BİR İŞLEM TARAFINDAN KULLANILIYOR")
            return
        }
        activeMicrophoneLease = microphoneLease
        scheduleNoCommandTimeout(sessionId, interactionId)

        try {
            speechInput.startListening(sessionId, language, object : SpeechInput.Callback {
                override fun onSpeechStarted(sessionId: Long) = dispatchOnMain {
                    if (activeSessionId != sessionId || activeInteractionId != interactionId) return@dispatchOnMain
                    cancelNoCommandTimeout()
                    Log.i(TAG, "command_speech_started interactionId=$interactionId session=$sessionId after_listen_ms=${SystemClock.elapsedRealtime() - recognitionStartedAtMs}")
                }

                override fun onSpeechEnded(sessionId: Long) = dispatchOnMain {
                    if (activeSessionId == sessionId && activeInteractionId == interactionId) {
                        speechEndedAtMs = SystemClock.elapsedRealtime()
                        Log.i(TAG, "command_speech_ended interactionId=$interactionId session=$sessionId")
                    }
                }

                override fun onRecordingReady(sessionId: Long, recording: AudioRecording) {
                    releaseMicrophoneLease(microphoneLease)
                    dispatchOnMain {
                        cancelNoCommandTimeout()
                        onRecordingReady(sessionId, language, interactionId, recording)
                    }
                }

                override fun onTranscriptReady(sessionId: Long, transcript: String) {
                    releaseMicrophoneLease(microphoneLease)
                    dispatchOnMain {
                        cancelNoCommandTimeout()
                        onTranscriptReady(sessionId, language, interactionId, transcript)
                    }
                }

                override fun onError(sessionId: Long, message: String) {
                    releaseMicrophoneLease(microphoneLease)
                    dispatchOnMain {
                        cancelNoCommandTimeout()
                        Log.w(TAG, "recognition_failed interactionId=$interactionId session=$sessionId")
                        reportFailure(sessionId, message)
                    }
                }
            })
        } catch (error: Exception) {
            releaseMicrophoneLease(microphoneLease)
            Log.w(TAG, "Ses tanıma başlatılamadı: tür=${error.javaClass.simpleName}")
            reportFailure(sessionId, "KONUŞMA TANIMA SERVİSİ BAŞLATILAMADI")
        }
    }

    /** Processes the utterance captured by the local wake-word listener without reopening the mic. */
    fun startWithWakeTranscript(sessionId: Long, transcript: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startWithWakeTranscript(sessionId, transcript) }
            return
        }
        if (closed || transcript.isBlank() || !robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)) return
        activeSessionId = sessionId
        recognitionStartedAtMs = SystemClock.elapsedRealtime()
        speechEndedAtMs = recognitionStartedAtMs
        requestStartedAtMs = 0L
        replyReadyAtMs = 0L
        cancelNoCommandTimeout()
        mainHandler.removeCallbacks(emotionDecayRunnable)
        val interactionId = UUID.randomUUID().toString()
        activeInteractionId = interactionId
        applyEmotionEvent(EmotionEvent.INTERACTION_STARTED)
        onCaptionChanged(null)
        val language = languageProvider()
        Log.i(TAG, "recognition_session_started interactionId=$interactionId session=$sessionId language=${language.tag} source=local_wake_handoff")
        Log.i(TAG, "wake_transcript_received interactionId=$interactionId chars=${transcript.length} preview=${VoiceDiagnostics.redactedPreview(transcript)}")
        onTranscriptReady(sessionId, language, interactionId, transcript)
    }

    fun cancelSession(sessionId: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelSession(sessionId) }
            return
        }
        if (activeSessionId != sessionId) return
        cancelNoCommandTimeout()
        Log.i(TAG, "interaction_cancelled interactionId=${activeInteractionId ?: "unknown"} session=$sessionId")
        speechInput.cancel()
        releaseActiveMicrophoneLease()
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

    /** Starts only an explicitly scheduled reminder, and only if RobotCore grants the idle-state transition. */
    fun speakProactiveReminder(message: String, onFinished: (Boolean) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { speakProactiveReminder(message, onFinished) }
            return
        }
        if (closed || message.isBlank() || activeSessionId != null) {
            onFinished(false)
            return
        }
        robotCore.dispatch(RobotEvent.ProactiveReminderStarted)
        if (robotCore.snapshot.activity != RobotActivityState.PROACTIVE_SPEAKING) {
            onFinished(false)
            return
        }
        val session = robotCore.snapshot.sessionId
        val interactionId = UUID.randomUUID().toString()
        activeSessionId = session
        activeInteractionId = interactionId
        speechOutput.speak(
            message,
            VoiceLanguage.TURKISH,
            personalityProfile.voiceDeliveryFor(emotionState),
            object : SpeechOutput.Callback {
                override fun onStarted() {
                    Log.i(TAG, "proactive_tts_started interactionId=$interactionId session=$session")
                }

                override fun onCompleted() = dispatchOnMain {
                    if (closed || activeSessionId != session || activeInteractionId != interactionId) return@dispatchOnMain
                    activeSessionId = null
                    activeInteractionId = null
                    robotCore.dispatch(RobotEvent.ProactiveReminderFinished(session))
                    Log.i(TAG, "proactive_tts_completed interactionId=$interactionId session=$session")
                    onFinished(true)
                }

                override fun onError(message: String) = dispatchOnMain {
                    if (closed || activeSessionId != session || activeInteractionId != interactionId) return@dispatchOnMain
                    activeSessionId = null
                    activeInteractionId = null
                    robotCore.dispatch(RobotEvent.ProactiveReminderFailed(session))
                    Log.w(TAG, "proactive_tts_failed interactionId=$interactionId session=$session reason=${message.take(64)}")
                    onFinished(false)
                }
            }
        )
    }

    fun close() {
        if (closed) return
        closed = true
        cancelNoCommandTimeout()
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
        val recognitionCompletedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "recognition_timing interactionId=$interactionId session=$session " +
            "since_listen_ms=${recognitionCompletedAt - recognitionStartedAtMs} " +
            "after_speech_end_ms=${if (speechEndedAtMs > 0L) recognitionCompletedAt - speechEndedAtMs else -1L}")
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
                var afterSpeech: (() -> String?)? = null
                var failure: String? = null
                var authenticationRequired = false
                try {
                    if (closed || activeInteractionId != interactionId ||
                        !robotCore.isCurrent(session, RobotActivityState.THINKING)
                    ) return@submit
                    val priorMessages = conversationHistory.recentMessages()
                    requestStartedAtMs = SystemClock.elapsedRealtime()
                    Log.i(
                        TAG,
                        "local_command_routing_started interactionId=$interactionId session=$session " +
                            "messageFingerprint=${VoiceDiagnostics.fingerprint(interactionId, transcript)} " +
                            "historyCount=${priorMessages.size}"
                    )
                    val localAlarmReply = nativeAlarmCommandProcessor?.process(transcript, interactionId)
                    val localClockReply = if (localAlarmReply == null) {
                        localClockCommandProcessor?.process(transcript, interactionId)
                    } else null
                    afterSpeech = localAlarmReply?.afterSpeech
                    val localReminderReply = if (localAlarmReply == null && localClockReply == null) {
                        reminderCommandProcessor?.process(transcript)
                    } else null
                    val route = LocalCommandRouteSelector.select(localAlarmReply, localClockReply, localReminderReply)
                    Log.i(TAG, "local_command_route_selected interactionId=$interactionId route=${route.name.lowercase(Locale.US)} " +
                        "handler_reached=${route != LocalCommandRoute.CONVERSATION_BACKEND}")
                    val generatedReply = when {
                        localAlarmReply != null -> ConversationReply(localAlarmReply.message)
                        localClockReply != null -> ConversationReply(localClockReply.message)
                        localReminderReply != null -> ConversationReply(localReminderReply)
                        else -> conversationEngine.respond(transcript, language, priorMessages, interactionId)
                    }
                    if (route == LocalCommandRoute.CONVERSATION_BACKEND) Log.i(TAG, "conversation_backend_call interactionId=$interactionId")
                    if (generatedReply.text.isNotBlank()) reply = generatedReply
                    else failure = "BULUT ASİSTANI BOŞ YANIT VERDİ"
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    authenticationRequired = (error as? ConversationFailure)?.code == "companion_auth_required"
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
                        if (authenticationRequired) onAuthenticationRequired()
                        reportFailure(session, failure ?: "KONUŞMA TAMAMLANAMADI")
                    } else {
                        applyEmotionEvent(EmotionEvent.RESPONSE_RECEIVED)
                        onCaptionChanged(generatedReply.text)
                        Log.i(
                            TAG,
                            "conversation_response_ready interactionId=$interactionId session=$session " +
                                "processing_ms=${SystemClock.elapsedRealtime() - requestStartedAtMs} " +
                                "chars=${generatedReply.text.length} " +
                                "preview=${VoiceDiagnostics.redactedPreview(generatedReply.text)}"
                        )
                        replyReadyAtMs = SystemClock.elapsedRealtime()
                        conversationHistory.appendTurn(transcript, generatedReply.text)
                        robotCore.dispatch(RobotEvent.ConversationReady(session, hasReply = true))
                        if (robotCore.isCurrent(session, RobotActivityState.SPEAKING)) {
                            speakResponse(session, interactionId, generatedReply.text, language, afterSpeech)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            reportFailure(session, "SOHBET İSTEĞİ BAŞLATILAMADI")
        }
    }

    private fun speakResponse(session: Long, interactionId: String, response: String, language: VoiceLanguage,
                              afterSpeech: (() -> String?)? = null) {
        if (closed || activeInteractionId != interactionId ||
            !robotCore.isCurrent(session, RobotActivityState.SPEAKING)
        ) return

        Log.i(TAG, "tts_playback_requested interactionId=$interactionId session=$session chars=${response.length}")
        speechOutput.speak(
            response,
            language,
            personalityProfile.voiceDeliveryFor(emotionState),
            object : SpeechOutput.Callback {
            override fun onStarted() {
                Log.i(
                    TAG,
                    "tts_playback_started interactionId=$interactionId session=$session chars=${response.length} " +
                        "after_reply_ms=${if (replyReadyAtMs > 0L) SystemClock.elapsedRealtime() - replyReadyAtMs else -1L} " +
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
                afterSpeech?.invoke()?.takeIf(String::isNotBlank)?.let { failure ->
                    speakPostActionFailure(interactionId, session, failure, language)
                }
            }

            override fun onError(message: String) = dispatchOnMain {
                Log.w(TAG, "tts_playback_failed interactionId=$interactionId session=$session")
                reportFailure(session, message)
            }
        })
    }

    private fun speakPostActionFailure(interactionId: String, session: Long, message: String, language: VoiceLanguage) {
        Log.i(TAG, "tts_post_action_failure_requested interactionId=$interactionId session=$session chars=${message.length}")
        speechOutput.speak(
            message,
            language,
            personalityProfile.voiceDeliveryFor(emotionState),
            object : SpeechOutput.Callback {
                override fun onStarted() { Log.i(TAG, "tts_post_action_failure_started interactionId=$interactionId session=$session") }
                override fun onCompleted() { Log.i(TAG, "tts_post_action_failure_completed interactionId=$interactionId session=$session") }
                override fun onError(message: String) { Log.w(TAG, "tts_post_action_failure_unavailable interactionId=$interactionId session=$session") }
            }
        )
    }

    private fun reportFailure(session: Long, message: String) {
        if (closed || robotCore.snapshot.sessionId != session) return
        if (robotCore.snapshot.activity !in VOICE_STATES) return
        cancelNoCommandTimeout()
        Log.w(TAG, "interaction_failed interactionId=${activeInteractionId ?: "unknown"} session=$session")
        applyEmotionEvent(EmotionEvent.INTERACTION_FAILED, scheduleDecay = true)
        speechInput.cancel()
        releaseActiveMicrophoneLease()
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
        cancelNoCommandTimeout()
        speechInput.cancel()
        releaseActiveMicrophoneLease()
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

    private fun releaseMicrophoneLease(lease: MicrophoneOwnership.Lease) {
        lease.close()
        if (activeMicrophoneLease === lease) activeMicrophoneLease = null
    }

    private fun releaseActiveMicrophoneLease() {
        activeMicrophoneLease?.let(::releaseMicrophoneLease)
    }

    private fun scheduleNoCommandTimeout(sessionId: Long, interactionId: String) {
        cancelNoCommandTimeout()
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (noCommandTimeout === timeout) noCommandTimeout = null
            if (closed || activeSessionId != sessionId || activeInteractionId != interactionId ||
                !robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)
            ) return@Runnable
            Log.i(TAG, "command_start_timeout interactionId=$interactionId session=$sessionId")
            reportFailure(sessionId, "KONUŞMA ZAMANI AŞILDI")
        }
        noCommandTimeout = timeout
        mainHandler.postDelayed(timeout, NO_COMMAND_TIMEOUT_MS)
    }

    private fun cancelNoCommandTimeout() {
        noCommandTimeout?.let(mainHandler::removeCallbacks)
        noCommandTimeout = null
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
        const val NO_COMMAND_TIMEOUT_MS = 8_000L
        val VOICE_STATES = setOf(
            RobotActivityState.LISTENING,
            RobotActivityState.THINKING,
            RobotActivityState.SPEAKING
        )
    }
}
