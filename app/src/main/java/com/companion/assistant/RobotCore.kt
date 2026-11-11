package com.companion.assistant

/** Exclusive activity controlled by RobotCore. Mood and connectivity are separate state dimensions. */
internal enum class RobotActivityState {
    BOOTING,
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    PROACTIVE_SPEAKING,
    SLEEPING,
    OBSERVING,
    ACTING,
    ERROR,
    CLOSED
}

/** Platform grants and user choices are explicit inputs; new capabilities default to disabled. */
internal data class RobotConsentState(
    val microphonePermissionGranted: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val cameraObservationEnabled: Boolean = false,
    val persistentMemoryEnabled: Boolean = false,
    val autonomyEnabled: Boolean = false,
    val autonomousSpeechEnabled: Boolean = false,
    val quietHoursActive: Boolean = false
)

internal data class RobotSnapshot(
    val activity: RobotActivityState = RobotActivityState.BOOTING,
    /** Monotonically changes whenever a voice session starts or is invalidated. */
    val sessionId: Long = 0L,
    val consent: RobotConsentState = RobotConsentState(),
    val errorMessage: String? = null
)

/** Inputs to the synchronous reducer. Call dispatch from one serialized thread (Android main thread). */
internal sealed interface RobotEvent {
    data object BootCompleted : RobotEvent
    data object FaceTapped : RobotEvent
    data class MicrophonePermissionChanged(val granted: Boolean) : RobotEvent
    data class CameraPermissionChanged(val granted: Boolean) : RobotEvent
    data class CameraConsentChanged(val enabled: Boolean) : RobotEvent
    data class PersistentMemoryConsentChanged(val enabled: Boolean) : RobotEvent
    data class AutonomyEnabledChanged(val enabled: Boolean) : RobotEvent
    data class AutonomousSpeechConsentChanged(val enabled: Boolean) : RobotEvent
    data class QuietHoursChanged(val active: Boolean) : RobotEvent
    data class RecordingCompleted(val sessionId: Long) : RobotEvent
    data class TranscriptReady(val sessionId: Long, val hasText: Boolean) : RobotEvent
    data class ConversationReady(val sessionId: Long, val hasReply: Boolean) : RobotEvent
    data class SpeechCompleted(val sessionId: Long) : RobotEvent
    data object ProactiveReminderStarted : RobotEvent
    data class ProactiveReminderFinished(val sessionId: Long) : RobotEvent
    data class ProactiveReminderFailed(val sessionId: Long) : RobotEvent
    data class SessionFailed(val sessionId: Long, val message: String) : RobotEvent
    data class ErrorDismissed(val sessionId: Long) : RobotEvent
    data object SleepRequested : RobotEvent
    data object WakeRequested : RobotEvent
    data object HostResumed : RobotEvent
    data object HostPaused : RobotEvent
    data object CancelActiveInteraction : RobotEvent
    data object CloseRequested : RobotEvent
}

/** Effects are executed by the Android host outside the reducer. */
internal sealed interface RobotEffect {
    data object RequestMicrophonePermission : RobotEffect
    data class StartListening(val sessionId: Long) : RobotEffect
    data class CancelVoiceSession(val sessionId: Long) : RobotEffect
}

/**
 * Small, synchronous state reducer. It has no Android or I/O dependencies so transitions can be
 * tested on the JVM. The host serializes dispatch calls and performs returned effects separately.
 */
internal class RobotCore(
    private val onSnapshotChanged: (previous: RobotSnapshot, current: RobotSnapshot) -> Unit = { _, _ -> }
) {
    private val ownerThread = Thread.currentThread()

    @Volatile
    var snapshot: RobotSnapshot = RobotSnapshot()
        private set

    private var waitingForMicrophonePermission = false
    private var hostResumed = false

    fun dispatch(event: RobotEvent): List<RobotEffect> {
        check(Thread.currentThread() === ownerThread) {
            "RobotCore events must be serialized by its owner thread."
        }
        if (snapshot.activity == RobotActivityState.CLOSED) return emptyList()

        val previous = snapshot
        val effects = reduce(event)
        val current = snapshot
        if (current != previous) onSnapshotChanged(previous, current)
        return effects
    }

    fun isCurrent(sessionId: Long, activity: RobotActivityState): Boolean {
        val current = snapshot
        return current.sessionId == sessionId && current.activity == activity
    }

    private fun reduce(event: RobotEvent): List<RobotEffect> = when (event) {
        RobotEvent.BootCompleted -> {
            if (snapshot.activity == RobotActivityState.BOOTING) {
                snapshot = snapshot.copy(activity = RobotActivityState.IDLE)
            }
            emptyList()
        }

        RobotEvent.FaceTapped -> handleFaceTapped()

        is RobotEvent.MicrophonePermissionChanged -> {
            snapshot = snapshot.copy(
                consent = snapshot.consent.copy(microphonePermissionGranted = event.granted)
            )
            when {
                !event.granted && snapshot.activity in VOICE_STATES -> cancelToIdle()
                event.granted && waitingForMicrophonePermission -> {
                    if (hostResumed) {
                        waitingForMicrophonePermission = false
                        startListening()
                    } else {
                        emptyList()
                    }
                }
                !event.granted && waitingForMicrophonePermission -> {
                    waitingForMicrophonePermission = false
                    showError("MİKROFON İZNİ VERİLMEDİ")
                    emptyList()
                }
                else -> emptyList()
            }
        }

        is RobotEvent.CameraPermissionChanged -> {
            snapshot = snapshot.copy(
                consent = snapshot.consent.copy(cameraPermissionGranted = event.granted)
            )
            if (!event.granted) {
                snapshot = snapshot.copy(consent = snapshot.consent.copy(cameraObservationEnabled = false))
            }
            emptyList()
        }

        is RobotEvent.CameraConsentChanged -> {
            snapshot = snapshot.copy(
                consent = snapshot.consent.copy(
                    cameraObservationEnabled = event.enabled && snapshot.consent.cameraPermissionGranted
                )
            )
            emptyList()
        }

        is RobotEvent.PersistentMemoryConsentChanged -> {
            snapshot = snapshot.copy(
                consent = snapshot.consent.copy(persistentMemoryEnabled = event.enabled)
            )
            emptyList()
        }

        is RobotEvent.AutonomyEnabledChanged -> {
            snapshot = snapshot.copy(consent = snapshot.consent.copy(autonomyEnabled = event.enabled))
            emptyList()
        }

        is RobotEvent.AutonomousSpeechConsentChanged -> {
            snapshot = snapshot.copy(
                consent = snapshot.consent.copy(autonomousSpeechEnabled = event.enabled)
            )
            emptyList()
        }

        is RobotEvent.QuietHoursChanged -> {
            snapshot = snapshot.copy(consent = snapshot.consent.copy(quietHoursActive = event.active))
            emptyList()
        }

        is RobotEvent.RecordingCompleted -> {
            if (isCurrent(event.sessionId, RobotActivityState.LISTENING)) {
                snapshot = snapshot.copy(activity = RobotActivityState.THINKING)
            }
            emptyList()
        }

        is RobotEvent.TranscriptReady -> {
            if (event.sessionId == snapshot.sessionId) {
                when (snapshot.activity) {
                    RobotActivityState.LISTENING -> {
                        if (event.hasText) snapshot = snapshot.copy(activity = RobotActivityState.THINKING)
                        else showError("KONUŞMA ANLAŞILAMADI")
                    }

                    RobotActivityState.THINKING -> {
                        if (!event.hasText) showError("KONUŞMA ANLAŞILAMADI")
                    }

                    else -> Unit
                }
            }
            emptyList()
        }

        is RobotEvent.ConversationReady -> {
            if (isCurrent(event.sessionId, RobotActivityState.THINKING)) {
                if (event.hasReply) snapshot = snapshot.copy(activity = RobotActivityState.SPEAKING)
                else showError("BULUT ASİSTANI BOŞ YANIT VERDİ")
            }
            emptyList()
        }

        is RobotEvent.SpeechCompleted -> {
            if (isCurrent(event.sessionId, RobotActivityState.SPEAKING)) {
                snapshot = snapshot.copy(activity = RobotActivityState.IDLE, errorMessage = null)
            }
            emptyList()
        }

        RobotEvent.ProactiveReminderStarted -> {
            val consent = snapshot.consent
            if (hostResumed && snapshot.activity == RobotActivityState.IDLE && consent.autonomyEnabled &&
                consent.autonomousSpeechEnabled && !consent.quietHoursActive
            ) {
                snapshot = snapshot.copy(
                    activity = RobotActivityState.PROACTIVE_SPEAKING,
                    sessionId = snapshot.sessionId + 1L,
                    errorMessage = null
                )
            }
            emptyList()
        }

        is RobotEvent.ProactiveReminderFinished -> {
            if (isCurrent(event.sessionId, RobotActivityState.PROACTIVE_SPEAKING)) {
                snapshot = snapshot.copy(activity = RobotActivityState.IDLE, errorMessage = null)
            }
            emptyList()
        }

        is RobotEvent.ProactiveReminderFailed -> {
            if (isCurrent(event.sessionId, RobotActivityState.PROACTIVE_SPEAKING)) {
                snapshot = snapshot.copy(activity = RobotActivityState.IDLE, errorMessage = null)
            }
            emptyList()
        }

        is RobotEvent.SessionFailed -> {
            if (event.sessionId == snapshot.sessionId && snapshot.activity in VOICE_STATES) {
                showError(event.message)
            }
            emptyList()
        }

        is RobotEvent.ErrorDismissed -> {
            if (event.sessionId == snapshot.sessionId && snapshot.activity == RobotActivityState.ERROR) {
                snapshot = snapshot.copy(activity = RobotActivityState.IDLE, errorMessage = null)
            }
            emptyList()
        }

        RobotEvent.SleepRequested -> {
            if (snapshot.activity == RobotActivityState.IDLE) {
                snapshot = snapshot.copy(activity = RobotActivityState.SLEEPING)
            }
            emptyList()
        }

        RobotEvent.WakeRequested -> {
            if (snapshot.activity == RobotActivityState.SLEEPING) {
                snapshot = snapshot.copy(activity = RobotActivityState.IDLE)
            }
            emptyList()
        }

        RobotEvent.HostResumed -> {
            hostResumed = true
            if (waitingForMicrophonePermission && snapshot.consent.microphonePermissionGranted) {
                waitingForMicrophonePermission = false
                startListening()
            } else {
                emptyList()
            }
        }

        RobotEvent.HostPaused -> {
            hostResumed = false
            if (snapshot.activity in VOICE_STATES) cancelToIdle() else emptyList()
        }

        RobotEvent.CancelActiveInteraction -> cancelToIdle()

        RobotEvent.CloseRequested -> {
            hostResumed = false
            waitingForMicrophonePermission = false
            val cancelEffect = activeVoiceSession()?.let(RobotEffect::CancelVoiceSession)
            snapshot = snapshot.copy(
                activity = RobotActivityState.CLOSED,
                sessionId = snapshot.sessionId + 1,
                errorMessage = null
            )
            if (cancelEffect == null) emptyList() else listOf(cancelEffect)
        }
    }

    private fun handleFaceTapped(): List<RobotEffect> {
        val activity = snapshot.activity
        if (!hostResumed || activity == RobotActivityState.BOOTING || activity == RobotActivityState.CLOSED) {
            return emptyList()
        }
        if (activity == RobotActivityState.LISTENING) return cancelToIdle()

        val oldSession = activeVoiceSession()
        if (activity == RobotActivityState.SLEEPING || activity == RobotActivityState.ERROR) {
            snapshot = snapshot.copy(activity = RobotActivityState.IDLE, errorMessage = null)
        }

        if (!snapshot.consent.microphonePermissionGranted) {
            waitingForMicrophonePermission = true
            val effects = mutableListOf<RobotEffect>()
            if (oldSession != null) effects += RobotEffect.CancelVoiceSession(oldSession)
            snapshot = snapshot.copy(
                activity = RobotActivityState.IDLE,
                sessionId = snapshot.sessionId + if (oldSession == null) 0L else 1L,
                errorMessage = null
            )
            effects += RobotEffect.RequestMicrophonePermission
            return effects
        }

        waitingForMicrophonePermission = false
        val effects = mutableListOf<RobotEffect>()
        if (oldSession != null) effects += RobotEffect.CancelVoiceSession(oldSession)
        val nextSession = snapshot.sessionId + 1L
        snapshot = snapshot.copy(
            activity = RobotActivityState.LISTENING,
            sessionId = nextSession,
            errorMessage = null
        )
        effects += RobotEffect.StartListening(nextSession)
        return effects
    }

    private fun startListening(): List<RobotEffect> {
        val sessionId = snapshot.sessionId + 1L
        snapshot = snapshot.copy(
            activity = RobotActivityState.LISTENING,
            sessionId = sessionId,
            errorMessage = null
        )
        return listOf(RobotEffect.StartListening(sessionId))
    }

    private fun cancelToIdle(): List<RobotEffect> {
        waitingForMicrophonePermission = false
        val activeSession = activeVoiceSession()
        if (activeSession == null && snapshot.activity == RobotActivityState.IDLE) return emptyList()
        snapshot = snapshot.copy(
            activity = RobotActivityState.IDLE,
            sessionId = snapshot.sessionId + if (activeSession == null) 0L else 1L,
            errorMessage = null
        )
        return if (activeSession == null) emptyList() else listOf(RobotEffect.CancelVoiceSession(activeSession))
    }

    private fun activeVoiceSession(): Long? =
        snapshot.sessionId.takeIf { snapshot.activity in VOICE_STATES }

    private fun showError(message: String) {
        snapshot = snapshot.copy(activity = RobotActivityState.ERROR, errorMessage = message)
    }

    private companion object {
        val VOICE_STATES = setOf(
            RobotActivityState.LISTENING,
            RobotActivityState.THINKING,
            RobotActivityState.SPEAKING,
            RobotActivityState.PROACTIVE_SPEAKING
        )
    }
}
