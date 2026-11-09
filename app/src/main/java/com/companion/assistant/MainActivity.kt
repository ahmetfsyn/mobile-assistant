package com.companion.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    private var faceView: FaceView? = null
    private var voiceController: VoiceInteractionController? = null
    private var audioDiagnosticView: AudioDiagnosticView? = null
    private var audioDiagnosticController: AudioOutputDiagnosticController? = null
    private var showingAudioDiagnostics = false
    private var microphonePermissionRequestPending = false
    private var robotCore: RobotCore? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissRobotError = Runnable {
        val snapshot = robotCore?.snapshot ?: return@Runnable
        if (snapshot.activity == RobotActivityState.ERROR) {
            dispatchRobotEvent(RobotEvent.ErrorDismissed(snapshot.sessionId))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        val face = FaceView(this)
        faceView = face
        val core = RobotCore(::onRobotSnapshotChanged)
        robotCore = core
        voiceController = VoiceInteractionController(
            robotCore = core,
            speechRecognizer = SpeechRecognizerFactory.create(),
            speechOutput = TextToSpeechManager(applicationContext),
            conversationEngine = ConversationEngineFactory.create(BuildConfig.COMPANION_BACKEND_URL),
            conversationHistory = SharedPreferencesConversationHistoryStore(applicationContext)
        )
        face.setOnFaceTapListener { onFaceTapped() }
        face.setOnDiagnosticTapListener { openAudioDiagnostics() }
        setContentView(face)
        dispatchRobotEvent(RobotEvent.BootCompleted)
        dispatchRobotEvent(RobotEvent.MicrophonePermissionChanged(hasMicrophonePermission()))
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        dispatchRobotEvent(RobotEvent.HostResumed)
        if (!microphonePermissionRequestPending) {
            dispatchRobotEvent(RobotEvent.MicrophonePermissionChanged(hasMicrophonePermission()))
        }
        if (!showingAudioDiagnostics) faceView?.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onPause() {
        dispatchRobotEvent(RobotEvent.HostPaused)
        closeAudioDiagnostics(showFace = true, restartFace = false)
        faceView?.stop()
        super.onPause()
    }

    override fun onBackPressed() {
        if (showingAudioDiagnostics) {
            closeAudioDiagnostics(showFace = true, restartFace = true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        microphonePermissionRequestPending = false

        dispatchRobotEvent(
            RobotEvent.MicrophonePermissionChanged(
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            )
        )
    }

    override fun onDestroy() {
        closeAudioDiagnostics(showFace = false, restartFace = false)
        dispatchRobotEvent(RobotEvent.CloseRequested)
        voiceController?.close()
        voiceController = null
        mainHandler.removeCallbacksAndMessages(null)
        robotCore = null
        faceView?.setOnDiagnosticTapListener(null)
        faceView?.destroy()
        faceView = null
        super.onDestroy()
    }

    private fun onFaceTapped() {
        dispatchRobotEvent(RobotEvent.FaceTapped)
    }

    private fun hasMicrophonePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun dispatchRobotEvent(event: RobotEvent) {
        val core = robotCore ?: return
        core.dispatch(event).forEach { effect ->
            when (effect) {
                RobotEffect.RequestMicrophonePermission -> {
                    if (!microphonePermissionRequestPending) {
                        microphonePermissionRequestPending = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
                        } else {
                            microphonePermissionRequestPending = false
                            dispatchRobotEvent(RobotEvent.MicrophonePermissionChanged(granted = true))
                        }
                    }
                }

                is RobotEffect.StartListening -> voiceController?.startListening(effect.sessionId)
                is RobotEffect.CancelVoiceSession -> voiceController?.cancelSession(effect.sessionId)
            }
        }
    }

    private fun onRobotSnapshotChanged(previous: RobotSnapshot, current: RobotSnapshot) {
        when (current.activity) {
            RobotActivityState.LISTENING -> {
                mainHandler.removeCallbacks(dismissRobotError)
                faceView?.showVoiceListening()
            }

            RobotActivityState.THINKING -> faceView?.showVoiceProcessing()
            RobotActivityState.SPEAKING -> faceView?.showVoiceSpeaking()
            RobotActivityState.ERROR -> {
                faceView?.showVoiceError(current.errorMessage ?: "KONUŞMA TAMAMLANAMADI")
                mainHandler.removeCallbacks(dismissRobotError)
                mainHandler.postDelayed(dismissRobotError, ERROR_DISPLAY_MS)
            }

            RobotActivityState.IDLE -> {
                mainHandler.removeCallbacks(dismissRobotError)
                if (previous.activity in ACTIVE_FACE_STATES) faceView?.finishVoiceInteraction()
            }

            RobotActivityState.BOOTING,
            RobotActivityState.SLEEPING,
            RobotActivityState.OBSERVING,
            RobotActivityState.ACTING,
            RobotActivityState.CLOSED -> Unit
        }
    }

    private fun openAudioDiagnostics() {
        if (showingAudioDiagnostics) return

        dispatchRobotEvent(RobotEvent.CancelActiveInteraction)
        faceView?.stop()
        showingAudioDiagnostics = true

        val diagnosticView = AudioDiagnosticView(this, object : AudioDiagnosticView.Listener {
            override fun onOutputSelected(output: AudioDiagnosticView.Output) {
                audioDiagnosticController?.testOutput(output)
            }

            override fun onReturnToFace() {
                closeAudioDiagnostics(showFace = true, restartFace = true)
            }
        })
        audioDiagnosticView = diagnosticView
        audioDiagnosticController = AudioOutputDiagnosticController(this, diagnosticView::showStatus)
        setContentView(diagnosticView)
        applyImmersiveFullscreen()
    }

    private fun closeAudioDiagnostics(showFace: Boolean, restartFace: Boolean) {
        if (!showingAudioDiagnostics && audioDiagnosticController == null && audioDiagnosticView == null) return

        audioDiagnosticController?.close()
        audioDiagnosticController = null
        audioDiagnosticView = null
        showingAudioDiagnostics = false

        if (showFace) {
            faceView?.let { face ->
                setContentView(face)
                applyImmersiveFullscreen()
                if (restartFace) face.start()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFullscreen() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val layout = window.attributes
            layout.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = layout
        }

        window.decorView.apply {
            setBackgroundColor(Color.BLACK)
            systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private companion object {
        const val REQUEST_MICROPHONE = 4101
        const val ERROR_DISPLAY_MS = 1800L
        val ACTIVE_FACE_STATES = setOf(
            RobotActivityState.LISTENING,
            RobotActivityState.THINKING,
            RobotActivityState.SPEAKING,
            RobotActivityState.ERROR
        )
    }
}
