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
    private val localClockCommandProcessor: LocalClockCommandProcessor? = null,
    private val sessionTimeoutMillisProvider: () -> Long = {
        ConversationSessionPolicy.timeoutMillis(ConversationSessionPolicy.DEFAULT_TIMEOUT_SECONDS)
    }
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
    private var conversationSessionTimeout: Runnable? = null
    private var followUpRetry: Runnable? = null
    private var conversationSessionId: Long? = null
    private var listeningForFollowUp = false
    private var bargeInMonitorId: String? = null
    private var bargeInTriggeredId: String? = null
    private var bargeInMicrophoneLease: MicrophoneOwnership.Lease? = null
    private var bargeInSpeechStartedAtMs = 0L
    private var currentSpokenResponse: String? = null
    private var currentUserTranscript: String? = null
    private var pendingCorrectionContext: String? = null
    private var bargeInRetry: Runnable? = null
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
        beginListening(sessionId, followUp = false)
    }

    private fun startFollowUpListening(sessionId: Long) {
        if (closed || !robotCore.isCurrent(sessionId, RobotActivityState.FOLLOW_UP_LISTENING)) return
        robotCore.dispatch(RobotEvent.FollowUpListeningStarted(sessionId))
        if (robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)) beginListening(sessionId, followUp = true)
    }

    private fun beginListening(sessionId: Long, followUp: Boolean) {
        if (closed || !robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)) return
        if (conversationSessionId != sessionId) {
            currentUserTranscript = null
            pendingCorrectionContext = null
        }
        activeSessionId = sessionId
        conversationSessionId = sessionId
        recognitionStartedAtMs = SystemClock.elapsedRealtime()
        speechEndedAtMs = 0L
        requestStartedAtMs = 0L
        replyReadyAtMs = 0L
        listeningForFollowUp = followUp
        cancelNoCommandTimeout()
        if (!followUp) mainHandler.removeCallbacks(emotionDecayRunnable)
        val interactionId = UUID.randomUUID().toString()
        activeInteractionId = interactionId
        applyEmotionEvent(EmotionEvent.INTERACTION_STARTED)
        onCaptionChanged(null)
        val language = languageProvider()
        Log.i(TAG, "recognition_session_started interactionId=$interactionId session=$sessionId language=${language.tag}")

        val microphoneLease = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.PUSH_TO_TALK)
        if (microphoneLease == null) {
            if (followUp) recoverFollowUpListening(sessionId, interactionId, "microphone_busy")
            else reportFailure(sessionId, "MİKROFON BAŞKA BİR İŞLEM TARAFINDAN KULLANILIYOR")
            return
        }
        activeMicrophoneLease = microphoneLease
        if (!followUp) scheduleNoCommandTimeout(sessionId, interactionId)

        try {
            speechInput.startListening(sessionId, language, object : SpeechInput.Callback {
                override fun onSpeechStarted(sessionId: Long) = dispatchOnMain {
                    if (activeSessionId != sessionId || activeInteractionId != interactionId) return@dispatchOnMain
                    cancelNoCommandTimeout()
                    if (followUp) scheduleConversationSessionTimeout(sessionId)
                    Log.i(TAG, "command_speech_started interactionId=$interactionId session=$sessionId follow_up=$followUp after_listen_ms=${SystemClock.elapsedRealtime() - recognitionStartedAtMs}")
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
                        cancelConversationSessionTimeout()
                        onRecordingReady(sessionId, language, interactionId, recording)
                    }
                }

                override fun onTranscriptReady(sessionId: Long, transcript: String) {
                    releaseMicrophoneLease(microphoneLease)
                    dispatchOnMain {
                        cancelNoCommandTimeout()
                        cancelConversationSessionTimeout()
                        onTranscriptReady(sessionId, language, interactionId, transcript)
                    }
                }

                override fun onError(sessionId: Long, message: String) {
                    releaseMicrophoneLease(microphoneLease)
                    dispatchOnMain {
                        cancelNoCommandTimeout()
                        Log.w(TAG, "recognition_failed interactionId=$interactionId session=$sessionId")
                        if (followUp) recoverFollowUpListening(sessionId, interactionId, "recognition_error")
                        else reportFailure(sessionId, message)
                    }
                }
            })
        } catch (error: Exception) {
            releaseMicrophoneLease(microphoneLease)
            Log.w(TAG, "Ses tanıma başlatılamadı: tür=${error.javaClass.simpleName}")
            if (followUp) recoverFollowUpListening(sessionId, interactionId, "start_failed")
            else reportFailure(sessionId, "KONUŞMA TANIMA SERVİSİ BAŞLATILAMADI")
        }
    }

    /** Processes the utterance captured by the local wake-word listener without reopening the mic. */
    fun startWithWakeTranscript(sessionId: Long, transcript: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startWithWakeTranscript(sessionId, transcript) }
            return
        }
        if (closed || transcript.isBlank() || !robotCore.isCurrent(sessionId, RobotActivityState.LISTENING)) return
        if (conversationSessionId != sessionId) {
            currentUserTranscript = null
            pendingCorrectionContext = null
        }
        activeSessionId = sessionId
        conversationSessionId = sessionId
        listeningForFollowUp = false
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
        cancelConversationSessionTimeout()
        cancelFollowUpRetry()
        cancelBargeInMonitor("session_cancelled")
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
        conversationSessionId = null
        currentSpokenResponse = null
        currentUserTranscript = null
        pendingCorrectionContext = null
        listeningForFollowUp = false
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
        currentUserTranscript = null
        pendingCorrectionContext = null
        currentSpokenResponse = message
        speechOutput.speak(
            message,
            VoiceLanguage.TURKISH,
            personalityProfile.voiceDeliveryFor(emotionState),
            object : SpeechOutput.Callback {
                override fun onStarted() {
                    Log.i(TAG, "proactive_tts_started interactionId=$interactionId session=$session")
                    armBargeInMonitor(session, interactionId, VoiceLanguage.TURKISH)
                }

                override fun onCompleted() = dispatchOnMain {
                    if (closed || activeSessionId != session || activeInteractionId != interactionId) return@dispatchOnMain
                    cancelBargeInMonitor("proactive_tts_completed")
                    activeSessionId = null
                    activeInteractionId = null
                    currentSpokenResponse = null
                    robotCore.dispatch(RobotEvent.ProactiveReminderFinished(session))
                    Log.i(TAG, "proactive_tts_completed interactionId=$interactionId session=$session")
                    onFinished(true)
                }

                override fun onError(message: String) = dispatchOnMain {
                    if (closed || activeSessionId != session || activeInteractionId != interactionId) return@dispatchOnMain
                    cancelBargeInMonitor("proactive_tts_failed")
                    activeSessionId = null
                    activeInteractionId = null
                    currentSpokenResponse = null
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
        cancelConversationSessionTimeout()
        cancelFollowUpRetry()
        cancelBargeInRetry()
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
        armBargeInMonitor(session, interactionId, language)
        val recognizer = recordedSpeechRecognizer
        if (recognizer == null) {
            recording.close()
            if (listeningForFollowUp) recoverFollowUpListening(session, interactionId, "recognizer_unavailable")
            else reportFailure(session, "KONUŞMA TANIMA YAPILANDIRMASI HATALI")
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
                    if (error != null) {
                        if (listeningForFollowUp) recoverFollowUpListening(session, interactionId, "stt_failed")
                        else reportFailure(session, error)
                    }
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
        if (transcript.isEmpty() && listeningForFollowUp) {
            recoverFollowUpListening(session, interactionId, "empty_transcript")
            return
        }
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
        val correctionContext = pendingCorrectionContext
        pendingCorrectionContext = null
        currentUserTranscript = transcript
        listeningForFollowUp = false
        cancelConversationSessionTimeout()
        applyEmotionEvent(EmotionEvent.TRANSCRIPT_RECEIVED)
        onCaptionChanged(transcript)
        armBargeInMonitor(session, interactionId, language)

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
                var selectedRoute = LocalCommandRoute.CONVERSATION_BACKEND
                try {
                    if (closed || activeInteractionId != interactionId ||
                        !robotCore.isCurrent(session, RobotActivityState.THINKING)
                    ) return@submit
                    val priorMessages = conversationHistory.recentMessages().toMutableList().apply {
                        correctionContext?.takeIf { context ->
                            none { it.role == "user" && it.content == context }
                        }?.let { add(ConversationMessage("user", it)) }
                    }.takeLast(8)
                    requestStartedAtMs = SystemClock.elapsedRealtime()
                    Log.i(
                        TAG,
                        "local_command_routing_started interactionId=$interactionId session=$session " +
                            "messageFingerprint=${VoiceDiagnostics.fingerprint(interactionId, transcript)} " +
                            "historyCount=${priorMessages.size}"
                    )
                    val localAlarmReply = nativeAlarmCommandProcessor?.process(transcript, interactionId, correctionContext)
                    val localClockReply = if (localAlarmReply == null) {
                        localClockCommandProcessor?.process(transcript, interactionId, correctionContext)
                    } else null
                    afterSpeech = localAlarmReply?.afterSpeech
                    val localReminderReply = if (localAlarmReply == null && localClockReply == null) {
                        reminderCommandProcessor?.process(transcript)
                    } else null
                    val dispatch = LocalCommandDispatcher.dispatch(localAlarmReply, localClockReply, localReminderReply)
                    selectedRoute = dispatch.route
                    Log.i(TAG, "local_command_route_selected interactionId=$interactionId route=${dispatch.route.name.lowercase(Locale.US)} " +
                        "status=${dispatch.result.status.name.lowercase(Locale.US)} " +
                        "handler_reached=${dispatch.result.status != LocalActionStatus.REMOTE_REQUIRED}")
                    val generatedReply = if (dispatch.result.status == LocalActionStatus.REMOTE_REQUIRED) {
                        Log.i(TAG, "conversation_backend_call interactionId=$interactionId execution_status=remote_required")
                        conversationEngine.respond(transcript, language, priorMessages, interactionId)
                    } else {
                        ConversationReply(dispatch.result.message)
                    }
                    if (generatedReply.text.isNotBlank()) reply = generatedReply
                    else failure = "BULUT ASİSTANI BOŞ YANIT VERDİ"
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    authenticationRequired = (error as? ConversationFailure)?.code == "companion_auth_required"
                    if (robotCore.isCurrent(session, RobotActivityState.THINKING)) {
                        Log.w(
                            TAG,
                            "conversation_failed interactionId=$interactionId session=$session " +
                                "execution_status=${if (selectedRoute == LocalCommandRoute.CONVERSATION_BACKEND) LocalActionStatus.REMOTE_FAILURE else LocalActionStatus.LOCAL_FAILURE} " +
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

    private fun armBargeInMonitor(session: Long, parentInteractionId: String, language: VoiceLanguage) {
        if (closed || bargeInMonitorId != null || activeSessionId != session ||
            robotCore.snapshot.activity !in setOf(
                RobotActivityState.THINKING,
                RobotActivityState.SPEAKING,
                RobotActivityState.PROACTIVE_SPEAKING
            )
        ) return
        val lease = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.BARGE_IN)
        if (lease == null) {
            Log.i(TAG, "barge_in_unavailable session=$session microphone_owner=${MicrophoneOwnership.currentOwner()}")
            return
        }
        val monitorId = UUID.randomUUID().toString()
        bargeInMonitorId = monitorId
        bargeInTriggeredId = null
        bargeInMicrophoneLease = lease
        bargeInSpeechStartedAtMs = 0L
        Log.i(TAG, "barge_in_armed interactionId=$monitorId parentInteractionId=$parentInteractionId session=$session " +
            "microphone_owner=${MicrophoneOwnership.currentOwner()} tts_active=${robotCore.isCurrent(session, RobotActivityState.SPEAKING)}")

        try {
            speechInput.startListening(session, language, object : SpeechInput.Callback {
                override fun onSpeechStarted(sessionId: Long) = dispatchOnMain {
                    if (bargeInMonitorId != monitorId || activeSessionId != sessionId) return@dispatchOnMain
                    bargeInSpeechStartedAtMs = SystemClock.elapsedRealtime()
                    Log.i(TAG, "barge_in_voice_activity interactionId=$monitorId session=$sessionId")
                    // PCM mode has no interim transcripts. Its VOICE_RECOGNITION source has an
                    // attached Android echo canceller; platform recognition decides from partial text.
                    if (speechInput is PcmSpeechInput) beginBargeIn(sessionId, monitorId)
                }

                override fun onPartialTranscript(sessionId: Long, transcript: String) = dispatchOnMain {
                    if (bargeInMonitorId != monitorId || activeSessionId != sessionId) return@dispatchOnMain
                    if (BargeInEchoFilter.isLikelyEcho(transcript, currentSpokenResponse)) {
                        Log.i(TAG, "barge_in_echo_suppressed interactionId=$monitorId chars=${transcript.length}")
                    } else {
                        beginBargeIn(sessionId, monitorId)
                    }
                }

                override fun onRecordingReady(sessionId: Long, recording: AudioRecording) {
                    releaseBargeInLease(lease)
                    dispatchOnMain {
                        if (bargeInMonitorId != monitorId || bargeInTriggeredId != monitorId) {
                            recording.close()
                            finishBargeInMonitor(monitorId)
                            return@dispatchOnMain
                        }
                        finishBargeInMonitor(monitorId)
                        onRecordingReady(sessionId, language, monitorId, recording)
                    }
                }

                override fun onTranscriptReady(sessionId: Long, transcript: String) {
                    releaseBargeInLease(lease)
                    dispatchOnMain {
                        if (bargeInMonitorId != monitorId || activeSessionId != sessionId) return@dispatchOnMain
                        if (bargeInTriggeredId != monitorId && !BargeInEchoFilter.isLikelyEcho(transcript, currentSpokenResponse)) {
                            beginBargeIn(sessionId, monitorId)
                        }
                        val accepted = bargeInTriggeredId == monitorId
                        finishBargeInMonitor(monitorId)
                        if (accepted) onTranscriptReady(sessionId, language, monitorId, transcript)
                        else {
                            Log.i(TAG, "barge_in_echo_result_ignored interactionId=$monitorId chars=${transcript.length}")
                            scheduleBargeInRearm(sessionId, language, parentInteractionId)
                        }
                    }
                }

                override fun onError(sessionId: Long, message: String) {
                    releaseBargeInLease(lease)
                    dispatchOnMain {
                        if (bargeInMonitorId != monitorId || activeSessionId != sessionId) return@dispatchOnMain
                        val wasTriggered = bargeInTriggeredId == monitorId
                        finishBargeInMonitor(monitorId)
                        if (wasTriggered) reportFailure(sessionId, message)
                        else scheduleBargeInRearm(sessionId, language, parentInteractionId)
                    }
                }
            })
        } catch (error: RuntimeException) {
            releaseBargeInLease(lease)
            finishBargeInMonitor(monitorId)
            Log.w(TAG, "barge_in_start_failed session=$session type=${error.javaClass.simpleName}")
        }
    }

    private fun beginBargeIn(session: Long, monitorId: String) {
        if (bargeInMonitorId != monitorId || activeSessionId != session || bargeInTriggeredId == monitorId) return
        val activity = robotCore.snapshot.activity
        if (activity !in setOf(RobotActivityState.THINKING, RobotActivityState.SPEAKING, RobotActivityState.PROACTIVE_SPEAKING)) return
        val detectedAt = bargeInSpeechStartedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val stopStartedAt = SystemClock.elapsedRealtime()
        cancelNoCommandTimeout()
        cancelConversationSessionTimeout()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        conversationEngine.cancelCurrentRequest()
        if (activity == RobotActivityState.SPEAKING || activity == RobotActivityState.PROACTIVE_SPEAKING) speechOutput.stop()
        currentSpokenResponse = null
        activeInteractionId = monitorId
        pendingCorrectionContext = currentUserTranscript
        listeningForFollowUp = false
        bargeInTriggeredId = monitorId
        robotCore.dispatch(RobotEvent.BargeInDetected(session))
        robotCore.dispatch(RobotEvent.BargeInListeningStarted(session))
        applyEmotionEvent(EmotionEvent.INTERACTION_STARTED)
        Log.i(TAG, "barge_in_detected interactionId=$monitorId session=$session " +
            "speech_to_tts_stop_ms=${SystemClock.elapsedRealtime() - detectedAt} " +
            "handling_ms=${SystemClock.elapsedRealtime() - stopStartedAt}")
    }

    private fun scheduleBargeInRearm(session: Long, language: VoiceLanguage, parentInteractionId: String) {
        if (closed || activeSessionId != session ||
            robotCore.snapshot.activity !in setOf(
                RobotActivityState.THINKING,
                RobotActivityState.SPEAKING,
                RobotActivityState.PROACTIVE_SPEAKING
            )
        ) return
        cancelBargeInRetry()
        lateinit var retry: Runnable
        retry = Runnable {
            if (bargeInRetry === retry) bargeInRetry = null
            armBargeInMonitor(session, parentInteractionId, language)
        }
        bargeInRetry = retry
        mainHandler.postDelayed(retry, BARGE_IN_RETRY_MS)
    }

    private fun finishBargeInMonitor(monitorId: String) {
        if (bargeInMonitorId != monitorId) return
        bargeInMonitorId = null
        bargeInTriggeredId = null
        bargeInSpeechStartedAtMs = 0L
        bargeInMicrophoneLease?.close()
        bargeInMicrophoneLease = null
    }

    private fun cancelBargeInMonitor(reason: String) {
        val monitorId = bargeInMonitorId ?: return
        Log.i(TAG, "barge_in_monitor_cancelled interactionId=$monitorId reason=$reason")
        finishBargeInMonitor(monitorId)
        speechInput.cancel()
        bargeInMicrophoneLease?.close()
        bargeInMicrophoneLease = null
    }

    private fun releaseBargeInLease(lease: MicrophoneOwnership.Lease) {
        lease.close()
        if (bargeInMicrophoneLease === lease) bargeInMicrophoneLease = null
    }

    private fun speakResponse(session: Long, interactionId: String, response: String, language: VoiceLanguage,
                              afterSpeech: (() -> String?)? = null) {
        if (closed || activeInteractionId != interactionId ||
            !robotCore.isCurrent(session, RobotActivityState.SPEAKING)
        ) return

        currentSpokenResponse = response
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
                armBargeInMonitor(session, interactionId, language)
            }

            override fun onCompleted() = dispatchOnMain {
                if (closed || activeSessionId != session || activeInteractionId != interactionId) {
                    return@dispatchOnMain
                }
                cancelBargeInMonitor("tts_completed")
                currentSpokenResponse = null
                applyEmotionEvent(EmotionEvent.INTERACTION_FINISHED, scheduleDecay = true)
                Log.i(TAG, "tts_playback_completed interactionId=$interactionId session=$session")
                val sessionTimeoutMs = sessionTimeoutMillisProvider().coerceAtLeast(0L)
                val continueConversation = afterSpeech == null && sessionTimeoutMs > 0L
                if (continueConversation) {
                    activeSessionId = session
                    activeInteractionId = null
                    conversationSessionId = session
                    pendingCorrectionContext = currentUserTranscript
                } else {
                    activeSessionId = null
                    activeInteractionId = null
                    conversationSessionId = null
                    pendingCorrectionContext = null
                }
                robotCore.dispatch(RobotEvent.SpeechCompleted(session, continueConversation))
                if (continueConversation && robotCore.isCurrent(session, RobotActivityState.FOLLOW_UP_LISTENING)) {
                    scheduleConversationSessionTimeout(session)
                    startFollowUpListening(session)
                }
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
        cancelConversationSessionTimeout()
        cancelFollowUpRetry()
        cancelBargeInMonitor("interaction_failed")
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
        conversationSessionId = null
        currentSpokenResponse = null
        listeningForFollowUp = false
        speechOutput.stop()
        onCaptionChanged(null)
        robotCore.dispatch(RobotEvent.SessionFailed(session, message))
    }

    private fun cancelSessionOnClose(sessionId: Long) {
        cancelNoCommandTimeout()
        cancelConversationSessionTimeout()
        cancelFollowUpRetry()
        cancelBargeInMonitor("activity_closed")
        speechInput.cancel()
        releaseActiveMicrophoneLease()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeSessionId = null
        activeInteractionId = null
        conversationSessionId = null
        currentSpokenResponse = null
        listeningForFollowUp = false
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

    private fun scheduleConversationSessionTimeout(sessionId: Long) {
        cancelConversationSessionTimeout()
        val timeoutMs = sessionTimeoutMillisProvider().coerceAtLeast(0L)
        if (timeoutMs == 0L) return
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (conversationSessionTimeout === timeout) conversationSessionTimeout = null
            if (closed || conversationSessionId != sessionId || activeSessionId != sessionId ||
                robotCore.snapshot.sessionId != sessionId ||
                robotCore.snapshot.activity !in setOf(RobotActivityState.FOLLOW_UP_LISTENING, RobotActivityState.LISTENING)
            ) return@Runnable
            Log.i(TAG, "conversation_session_timeout session=$sessionId timeout_ms=$timeoutMs")
            robotCore.dispatch(RobotEvent.ConversationSessionTimedOut(sessionId)).forEach { effect ->
                if (effect is RobotEffect.CancelVoiceSession) cancelSession(effect.sessionId)
            }
        }
        conversationSessionTimeout = timeout
        mainHandler.postDelayed(timeout, timeoutMs)
        Log.i(TAG, "conversation_session_timeout_scheduled session=$sessionId timeout_ms=$timeoutMs")
    }

    private fun cancelConversationSessionTimeout() {
        conversationSessionTimeout?.let(mainHandler::removeCallbacks)
        conversationSessionTimeout = null
    }

    private fun recoverFollowUpListening(sessionId: Long, interactionId: String, reason: String) {
        if (closed || activeSessionId != sessionId || activeInteractionId != interactionId ||
            conversationSessionId != sessionId
        ) return
        cancelNoCommandTimeout()
        releaseActiveMicrophoneLease()
        speechInput.cancel()
        recognitionTask?.cancel(true)
        recognitionTask = null
        activeRecording?.close()
        activeRecording = null
        activeInteractionId = null
        listeningForFollowUp = true
        onCaptionChanged(null)
        if (robotCore.snapshot.activity in setOf(RobotActivityState.LISTENING, RobotActivityState.THINKING)) {
            robotCore.dispatch(RobotEvent.FollowUpListeningEnded(sessionId))
        }
        Log.i(TAG, "follow_up_listening_retry session=$sessionId reason=$reason")
        // Keep the deadline established after the last reply. Silent recognition retries must not
        // extend the open-mic session forever; real speech refreshes it in onSpeechStarted().
        if (ConversationSessionPolicy.shouldArmTimeoutAfterRetry(conversationSessionTimeout != null)) {
            scheduleConversationSessionTimeout(sessionId)
        }
        cancelFollowUpRetry()
        lateinit var retry: Runnable
        retry = Runnable {
            if (followUpRetry === retry) followUpRetry = null
            if (!closed && conversationSessionId == sessionId &&
                robotCore.isCurrent(sessionId, RobotActivityState.FOLLOW_UP_LISTENING)
            ) startFollowUpListening(sessionId)
        }
        followUpRetry = retry
        mainHandler.postDelayed(retry, FOLLOW_UP_RETRY_MS)
    }

    private fun cancelFollowUpRetry() {
        followUpRetry?.let(mainHandler::removeCallbacks)
        followUpRetry = null
    }

    private fun cancelBargeInRetry() {
        bargeInRetry?.let(mainHandler::removeCallbacks)
        bargeInRetry = null
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
        const val FOLLOW_UP_RETRY_MS = 650L
        const val BARGE_IN_RETRY_MS = 400L
        val VOICE_STATES = setOf(
            RobotActivityState.LISTENING,
            RobotActivityState.THINKING,
            RobotActivityState.SPEAKING,
            RobotActivityState.PROACTIVE_SPEAKING,
            RobotActivityState.INTERRUPTED,
            RobotActivityState.FOLLOW_UP_LISTENING
        )
    }
}
