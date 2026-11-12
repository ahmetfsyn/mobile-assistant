package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotCoreTest {
    @Test
    fun launchStartsSleepingAndFaceTapAnimatesBeforeListening() {
        val core = readyCoreWithMicrophone()
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)

        assertTrue(core.dispatch(RobotEvent.FaceTapped).isEmpty())
        assertEquals(RobotActivityState.WAKING, core.snapshot.activity)
        val wakingSession = core.snapshot.sessionId
        assertTrue(core.dispatch(RobotEvent.FaceTapped).isEmpty())
        assertEquals(wakingSession, core.snapshot.sessionId)

        assertEquals(
            listOf(RobotEffect.StartListening(wakingSession + 1L)),
            core.dispatch(RobotEvent.WakeAnimationCompleted(wakingSession))
        )
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
    }

    @Test
    fun microphonePermissionIsRequestedAfterTheWakeAnimation() {
        val core = readyCore()
        core.dispatch(RobotEvent.FaceTapped)
        val wakingSession = core.snapshot.sessionId
        assertEquals(RobotActivityState.WAKING, core.snapshot.activity)

        assertEquals(
            listOf(RobotEffect.RequestMicrophonePermission),
            core.dispatch(RobotEvent.WakeAnimationCompleted(wakingSession))
        )
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)
        assertEquals(
            listOf(RobotEffect.StartListening(wakingSession + 1L)),
            core.dispatch(RobotEvent.MicrophonePermissionChanged(granted = true))
        )
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
        assertTrue(core.snapshot.consent.microphonePermissionGranted)
    }

    @Test
    fun deniedPermissionShowsErrorThenReturnsToSleep() {
        val core = readyCore()
        core.dispatch(RobotEvent.FaceTapped)
        val wakingSession = core.snapshot.sessionId
        core.dispatch(RobotEvent.WakeAnimationCompleted(wakingSession))

        assertTrue(core.dispatch(RobotEvent.MicrophonePermissionChanged(granted = false)).isEmpty())
        assertEquals(RobotActivityState.ERROR, core.snapshot.activity)
        assertEquals("MİKROFON İZNİ VERİLMEDİ", core.snapshot.errorMessage)

        val errorSession = core.snapshot.sessionId
        core.dispatch(RobotEvent.ErrorDismissed(errorSession))
        assertEquals(RobotActivityState.RETURNING_TO_SLEEP, core.snapshot.activity)
        core.dispatch(RobotEvent.SleepAnimationCompleted(core.snapshot.sessionId))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
        assertNull(core.snapshot.errorMessage)
    }

    @Test
    fun voiceInteractionUsesCoreStatesAndSleepsOnlyAfterSpeechCompletes() {
        val core = readyCoreWithMicrophone()
        val started = startListening(core)
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)

        core.dispatch(RobotEvent.RecordingCompleted(started.sessionId))
        assertEquals(RobotActivityState.THINKING, core.snapshot.activity)
        core.dispatch(RobotEvent.TranscriptReady(started.sessionId, hasText = true))
        core.dispatch(RobotEvent.ConversationReady(started.sessionId, hasReply = true))
        assertEquals(RobotActivityState.SPEAKING, core.snapshot.activity)

        core.dispatch(RobotEvent.SleepRequested)
        assertEquals(RobotActivityState.SPEAKING, core.snapshot.activity)
        core.dispatch(RobotEvent.SpeechCompleted(started.sessionId))
        assertEquals(RobotActivityState.RETURNING_TO_SLEEP, core.snapshot.activity)

        core.dispatch(RobotEvent.SleepAnimationCompleted(core.snapshot.sessionId))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
    }

    @Test
    fun tappingDuringSpeechCancelsTtsAndIgnoresItsLateCompletion() {
        val core = readyCoreWithMicrophone()
        val started = startListening(core)
        core.dispatch(RobotEvent.RecordingCompleted(started.sessionId))
        core.dispatch(RobotEvent.TranscriptReady(started.sessionId, hasText = true))
        core.dispatch(RobotEvent.ConversationReady(started.sessionId, hasReply = true))
        assertEquals(RobotActivityState.SPEAKING, core.snapshot.activity)

        assertEquals(
            listOf(RobotEffect.CancelVoiceSession(started.sessionId)),
            core.dispatch(RobotEvent.FaceTapped)
        )
        assertEquals(RobotActivityState.WAKING, core.snapshot.activity)

        core.dispatch(RobotEvent.SpeechCompleted(started.sessionId))
        assertEquals(RobotActivityState.WAKING, core.snapshot.activity)
        assertTrue(core.dispatch(RobotEvent.WakeAnimationCompleted(core.snapshot.sessionId)).single()
            is RobotEffect.StartListening)
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
    }

    @Test
    fun staleWakeCompletionIsIgnoredAfterCancellation() {
        val core = readyCoreWithMicrophone()
        core.dispatch(RobotEvent.FaceTapped)
        val wakingSession = core.snapshot.sessionId
        core.dispatch(RobotEvent.CancelActiveInteraction)
        assertEquals(RobotActivityState.RETURNING_TO_SLEEP, core.snapshot.activity)

        core.dispatch(RobotEvent.WakeAnimationCompleted(wakingSession))
        assertEquals(RobotActivityState.RETURNING_TO_SLEEP, core.snapshot.activity)
        core.dispatch(RobotEvent.SleepAnimationCompleted(core.snapshot.sessionId))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
    }

    @Test
    fun tappingDuringListeningCancelsAndReturnsToSleep() {
        val core = readyCoreWithMicrophone()
        val started = startListening(core)

        assertEquals(
            listOf(RobotEffect.CancelVoiceSession(started.sessionId)),
            core.dispatch(RobotEvent.FaceTapped)
        )
        assertEquals(RobotActivityState.RETURNING_TO_SLEEP, core.snapshot.activity)
        core.dispatch(RobotEvent.SleepAnimationCompleted(core.snapshot.sessionId))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
    }

    @Test
    fun tappingDuringThinkingCancelsOldSessionThenWakesIntoNewListeningSession() {
        val core = readyCoreWithMicrophone()
        val first = startListening(core)
        core.dispatch(RobotEvent.RecordingCompleted(first.sessionId))

        assertEquals(
            listOf(RobotEffect.CancelVoiceSession(first.sessionId)),
            core.dispatch(RobotEvent.FaceTapped)
        )
        assertEquals(RobotActivityState.WAKING, core.snapshot.activity)
        val wakeSession = core.snapshot.sessionId
        assertEquals(
            listOf(RobotEffect.StartListening(wakeSession + 1L)),
            core.dispatch(RobotEvent.WakeAnimationCompleted(wakeSession))
        )
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)

        core.dispatch(RobotEvent.ConversationReady(first.sessionId, hasReply = true))
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
    }

    @Test
    fun speechRecognitionFailureReturnsThroughErrorAndSleepStates() {
        val core = readyCoreWithMicrophone()
        val started = startListening(core)
        core.dispatch(RobotEvent.SessionFailed(started.sessionId, "TANIMA HATASI"))
        assertEquals(RobotActivityState.ERROR, core.snapshot.activity)

        core.dispatch(RobotEvent.ErrorDismissed(core.snapshot.sessionId))
        assertEquals(RobotActivityState.RETURNING_TO_SLEEP, core.snapshot.activity)
        core.dispatch(RobotEvent.SleepAnimationCompleted(core.snapshot.sessionId))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
    }

    @Test
    fun pausingDuringVoiceCancelsSessionAndSettlesImmediatelyToSleep() {
        val core = readyCoreWithMicrophone()
        val started = startListening(core)

        assertEquals(
            listOf(RobotEffect.CancelVoiceSession(started.sessionId)),
            core.dispatch(RobotEvent.HostPaused)
        )
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
        assertFalse(core.isCurrent(started.sessionId, RobotActivityState.LISTENING))

        core.dispatch(RobotEvent.ConversationReady(started.sessionId, hasReply = true))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
    }

    @Test
    fun pausingDuringWakeIgnoresLateWakeAnimationCallback() {
        val core = readyCoreWithMicrophone()
        core.dispatch(RobotEvent.FaceTapped)
        val wakingSession = core.snapshot.sessionId
        core.dispatch(RobotEvent.HostPaused)

        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
        core.dispatch(RobotEvent.WakeAnimationCompleted(wakingSession))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
    }

    @Test
    fun remindersCanSpeakFromSleepAndThenReturnTheFaceToSleep() {
        val core = readyCore()
        core.dispatch(RobotEvent.AutonomyEnabledChanged(true))
        core.dispatch(RobotEvent.AutonomousSpeechConsentChanged(true))
        core.dispatch(RobotEvent.QuietHoursChanged(false))

        core.dispatch(RobotEvent.ProactiveReminderStarted)
        assertEquals(RobotActivityState.PROACTIVE_SPEAKING, core.snapshot.activity)
        val session = core.snapshot.sessionId
        core.dispatch(RobotEvent.ProactiveReminderFinished(session))
        assertEquals(RobotActivityState.RETURNING_TO_SLEEP, core.snapshot.activity)
        core.dispatch(RobotEvent.SleepAnimationCompleted(core.snapshot.sessionId))
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
    }

    @Test
    fun proactiveSpeechRequiresConsentQuietHoursAndAvailableCoreState() {
        val core = readyCoreWithMicrophone()
        core.dispatch(RobotEvent.AutonomyEnabledChanged(true))
        core.dispatch(RobotEvent.AutonomousSpeechConsentChanged(true))
        core.dispatch(RobotEvent.QuietHoursChanged(false))

        core.dispatch(RobotEvent.ProactiveReminderStarted)
        assertEquals(RobotActivityState.PROACTIVE_SPEAKING, core.snapshot.activity)
        core.dispatch(RobotEvent.ProactiveReminderFinished(core.snapshot.sessionId))
        finishSleep(core)

        core.dispatch(RobotEvent.QuietHoursChanged(true))
        core.dispatch(RobotEvent.ProactiveReminderStarted)
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)
        core.dispatch(RobotEvent.QuietHoursChanged(false))
        core.dispatch(RobotEvent.AutonomyEnabledChanged(false))
        core.dispatch(RobotEvent.ProactiveReminderStarted)
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)

        core.dispatch(RobotEvent.AutonomyEnabledChanged(true))
        core.dispatch(RobotEvent.FaceTapped)
        val wakeSession = core.snapshot.sessionId
        core.dispatch(RobotEvent.WakeAnimationCompleted(wakeSession))
        val voiceSession = core.snapshot.sessionId
        core.dispatch(RobotEvent.ProactiveReminderStarted)
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
        assertEquals(voiceSession, core.snapshot.sessionId)
    }

    @Test
    fun optionalCapabilitiesRequireExplicitConsent() {
        val core = readyCore()
        assertFalse(core.snapshot.consent.cameraObservationEnabled)
        assertFalse(core.snapshot.consent.persistentMemoryEnabled)
        assertFalse(core.snapshot.consent.autonomousSpeechEnabled)

        core.dispatch(RobotEvent.PersistentMemoryConsentChanged(enabled = true))
        assertTrue(core.snapshot.consent.persistentMemoryEnabled)
        core.dispatch(RobotEvent.PersistentMemoryConsentChanged(enabled = false))
        assertFalse(core.snapshot.consent.persistentMemoryEnabled)

        core.dispatch(RobotEvent.CameraConsentChanged(enabled = true))
        assertFalse(core.snapshot.consent.cameraObservationEnabled)
        core.dispatch(RobotEvent.CameraPermissionChanged(granted = true))
        core.dispatch(RobotEvent.CameraConsentChanged(enabled = true))
        assertTrue(core.snapshot.consent.cameraObservationEnabled)
    }

    @Test
    fun localWakeDetectionOpensAttentiveFaceWithoutStartingSpeechRecognition() {
        val core = readyCoreWithMicrophone()
        core.dispatch(RobotEvent.WakeWordMonitoringChanged(true))

        assertTrue(core.dispatch(RobotEvent.WakeWordDetected).isEmpty())
        assertEquals(RobotActivityState.WAKING, core.snapshot.activity)
        val wakeSession = core.snapshot.sessionId
        assertTrue(core.dispatch(RobotEvent.WakeWordDetected).isEmpty())
        assertEquals(wakeSession, core.snapshot.sessionId)

        assertTrue(core.dispatch(RobotEvent.WakeAnimationCompleted(wakeSession)).isEmpty())
        assertEquals(RobotActivityState.AWAKE, core.snapshot.activity)
        assertEquals("UYANDIM · DOKUN VE KONUŞ", RobotFacePresenter.forActivity(core.snapshot.activity).status)

        assertEquals(
            listOf(RobotEffect.StartListening(wakeSession + 1L)),
            core.dispatch(RobotEvent.FaceTapped)
        )
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
    }

    @Test
    fun localWakeDetectionRequiresOptInPermissionAndSleepingState() {
        val noOptIn = readyCoreWithMicrophone()
        noOptIn.dispatch(RobotEvent.WakeWordDetected)
        assertEquals(RobotActivityState.SLEEPING, noOptIn.snapshot.activity)

        val noPermission = readyCore()
        noPermission.dispatch(RobotEvent.WakeWordMonitoringChanged(true))
        noPermission.dispatch(RobotEvent.WakeWordDetected)
        assertEquals(RobotActivityState.SLEEPING, noPermission.snapshot.activity)

        val active = readyCoreWithMicrophone()
        active.dispatch(RobotEvent.WakeWordMonitoringChanged(true))
        active.dispatch(RobotEvent.FaceTapped)
        active.dispatch(RobotEvent.WakeWordDetected)
        assertEquals(RobotActivityState.WAKING, active.snapshot.activity)
    }

    @Test
    fun facePresentationMatchesSleepWakeAndVoiceState() {
        assertEquals(FaceExpression.SLEEPING, RobotFacePresenter.forActivity(RobotActivityState.SLEEPING).expression)
        assertEquals(FaceExpression.ATTENTIVE, RobotFacePresenter.forActivity(RobotActivityState.WAKING).expression)
        assertEquals(FaceExpression.ATTENTIVE, RobotFacePresenter.forActivity(RobotActivityState.LISTENING).expression)
        assertEquals(FaceExpression.CURIOUS, RobotFacePresenter.forActivity(RobotActivityState.THINKING).expression)
        assertEquals(FaceExpression.HAPPY, RobotFacePresenter.forActivity(RobotActivityState.SPEAKING).expression)
        assertEquals(
            FaceExpression.HAPPY,
            RobotFacePresenter.forActivity(RobotActivityState.PROACTIVE_SPEAKING).expression
        )
        assertEquals(
            FaceExpression.SLEEPING,
            RobotFacePresenter.forActivity(RobotActivityState.RETURNING_TO_SLEEP).expression
        )
        assertTrue(RobotFacePresenter.forActivity(RobotActivityState.WAKING).durationMs > 0L)
        assertTrue(RobotFacePresenter.forActivity(RobotActivityState.RETURNING_TO_SLEEP).durationMs > 0L)
    }

    private fun readyCore(): RobotCore = RobotCore().apply {
        dispatch(RobotEvent.BootCompleted)
        dispatch(RobotEvent.HostResumed)
    }

    private fun readyCoreWithMicrophone(): RobotCore = readyCore().apply {
        dispatch(RobotEvent.MicrophonePermissionChanged(granted = true))
    }

    private fun startListening(core: RobotCore): RobotEffect.StartListening {
        core.dispatch(RobotEvent.FaceTapped)
        return core.dispatch(RobotEvent.WakeAnimationCompleted(core.snapshot.sessionId))
            .single() as RobotEffect.StartListening
    }

    private fun finishSleep(core: RobotCore) {
        core.dispatch(RobotEvent.SleepAnimationCompleted(core.snapshot.sessionId))
    }
}
