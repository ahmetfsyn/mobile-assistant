package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotCoreTest {
    @Test
    fun microphonePermissionIsRequestedBeforeListening() {
        val core = readyCore()

        assertEquals(
            listOf(RobotEffect.RequestMicrophonePermission),
            core.dispatch(RobotEvent.FaceTapped)
        )
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)

        assertEquals(
            listOf(RobotEffect.StartListening(1L)),
            core.dispatch(RobotEvent.MicrophonePermissionChanged(granted = true))
        )
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
        assertTrue(core.snapshot.consent.microphonePermissionGranted)
    }

    @Test
    fun deniedMicrophonePermissionShowsRecoverableErrorWithoutStartingRecorder() {
        val core = readyCore()
        core.dispatch(RobotEvent.FaceTapped)

        assertTrue(core.dispatch(RobotEvent.MicrophonePermissionChanged(granted = false)).isEmpty())
        assertEquals(RobotActivityState.ERROR, core.snapshot.activity)
        assertEquals("MİKROFON İZNİ VERİLMEDİ", core.snapshot.errorMessage)

        core.dispatch(RobotEvent.ErrorDismissed(core.snapshot.sessionId))
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)
        assertNull(core.snapshot.errorMessage)
    }

    @Test
    fun permissionGrantWhilePausedWaitsUntilActivityResumes() {
        val core = readyCore()
        core.dispatch(RobotEvent.FaceTapped)
        core.dispatch(RobotEvent.HostPaused)

        assertTrue(core.dispatch(RobotEvent.MicrophonePermissionChanged(granted = true)).isEmpty())
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)
        assertEquals(
            listOf(RobotEffect.StartListening(1L)),
            core.dispatch(RobotEvent.HostResumed)
        )
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
    }

    @Test
    fun voiceTurnTransitionsThroughThinkingAndSpeaking() {
        val core = readyCoreWithMicrophone()
        val start = core.dispatch(RobotEvent.FaceTapped).single() as RobotEffect.StartListening
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)

        core.dispatch(RobotEvent.RecordingCompleted(start.sessionId))
        assertEquals(RobotActivityState.THINKING, core.snapshot.activity)
        core.dispatch(RobotEvent.TranscriptReady(start.sessionId, hasText = true))
        core.dispatch(RobotEvent.ConversationReady(start.sessionId, hasReply = true))
        assertEquals(RobotActivityState.SPEAKING, core.snapshot.activity)

        core.dispatch(RobotEvent.SpeechCompleted(start.sessionId))
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)
        assertNull(core.snapshot.errorMessage)
    }

    @Test
    fun platformTranscriptMovesDirectlyFromListeningToThinking() {
        val core = readyCoreWithMicrophone()
        val start = core.dispatch(RobotEvent.FaceTapped).single() as RobotEffect.StartListening
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)

        core.dispatch(RobotEvent.TranscriptReady(start.sessionId, hasText = true))
        assertEquals(RobotActivityState.THINKING, core.snapshot.activity)
    }

    @Test
    fun emptyPlatformTranscriptShowsRecoverableError() {
        val core = readyCoreWithMicrophone()
        val start = core.dispatch(RobotEvent.FaceTapped).single() as RobotEffect.StartListening

        core.dispatch(RobotEvent.TranscriptReady(start.sessionId, hasText = false))

        assertEquals(RobotActivityState.ERROR, core.snapshot.activity)
        assertEquals("KONUŞMA ANLAŞILAMADI", core.snapshot.errorMessage)
    }

    @Test
    fun newTapCancelsOldSessionAndRejectsItsLateEvents() {
        val core = readyCoreWithMicrophone()
        val first = core.dispatch(RobotEvent.FaceTapped).single() as RobotEffect.StartListening
        core.dispatch(RobotEvent.RecordingCompleted(first.sessionId))

        assertEquals(
            listOf(
                RobotEffect.CancelVoiceSession(first.sessionId),
                RobotEffect.StartListening(first.sessionId + 1L)
            ),
            core.dispatch(RobotEvent.FaceTapped)
        )
        assertEquals(first.sessionId + 1L, core.snapshot.sessionId)
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)

        core.dispatch(RobotEvent.ConversationReady(first.sessionId, hasReply = true))
        assertEquals(RobotActivityState.LISTENING, core.snapshot.activity)
    }

    @Test
    fun hostPauseCancelsActiveSessionAndIgnoresLateResponse() {
        val core = readyCoreWithMicrophone()
        val started = core.dispatch(RobotEvent.FaceTapped).single() as RobotEffect.StartListening

        assertEquals(
            listOf(RobotEffect.CancelVoiceSession(started.sessionId)),
            core.dispatch(RobotEvent.HostPaused)
        )
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)
        assertFalse(core.isCurrent(started.sessionId, RobotActivityState.LISTENING))

        core.dispatch(RobotEvent.ConversationReady(started.sessionId, hasReply = true))
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)
    }

    @Test
    fun cancellingForDiagnosticsKeepsHostAvailableForNextFaceTap() {
        val core = readyCoreWithMicrophone()
        val first = core.dispatch(RobotEvent.FaceTapped).single() as RobotEffect.StartListening

        assertEquals(
            listOf(RobotEffect.CancelVoiceSession(first.sessionId)),
            core.dispatch(RobotEvent.CancelActiveInteraction)
        )
        assertEquals(
            listOf(RobotEffect.StartListening(first.sessionId + 2L)),
            core.dispatch(RobotEvent.FaceTapped)
        )
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
    fun sleepAndWakeAreSerializedStateTransitions() {
        val core = readyCore()
        core.dispatch(RobotEvent.SleepRequested)
        assertEquals(RobotActivityState.SLEEPING, core.snapshot.activity)

        core.dispatch(RobotEvent.WakeRequested)
        assertEquals(RobotActivityState.IDLE, core.snapshot.activity)
    }

    private fun readyCore(): RobotCore = RobotCore().apply {
        dispatch(RobotEvent.BootCompleted)
        dispatch(RobotEvent.HostResumed)
    }

    private fun readyCoreWithMicrophone(): RobotCore = readyCore().apply {
        dispatch(RobotEvent.MicrophonePermissionChanged(granted = true))
    }
}
