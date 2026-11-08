package com.companion.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    private var faceView: FaceView? = null
    private var voiceController: VoiceInteractionController? = null
    private var audioDiagnosticView: AudioDiagnosticView? = null
    private var audioDiagnosticController: AudioOutputDiagnosticController? = null
    private var showingAudioDiagnostics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        val face = FaceView(this)
        faceView = face
        voiceController = VoiceInteractionController(
            faceView = face,
            speechRecognizer = SpeechRecognizerFactory.create(),
            speechOutput = TextToSpeechManager(applicationContext),
            conversationEngine = ConversationEngineFactory.create(BuildConfig.COMPANION_BACKEND_URL),
            conversationHistory = SharedPreferencesConversationHistoryStore(applicationContext)
        )
        face.setOnFaceTapListener { onFaceTapped() }
        face.setOnDiagnosticTapListener { openAudioDiagnostics() }
        setContentView(face)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        if (!showingAudioDiagnostics) faceView?.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onPause() {
        voiceController?.onHostPaused()
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

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            voiceController?.toggleListening()
        } else {
            faceView?.showVoiceError("MİKROFON İZNİ VERİLMEDİ")
        }
    }

    override fun onDestroy() {
        closeAudioDiagnostics(showFace = false, restartFace = false)
        voiceController?.close()
        voiceController = null
        faceView?.setOnDiagnosticTapListener(null)
        faceView?.destroy()
        faceView = null
        super.onDestroy()
    }

    private fun onFaceTapped() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            voiceController?.toggleListening()
            return
        }

        faceView?.showVoiceError("SESLİ İLETİŞİM İÇİN MİKROFON İZNİ GEREKLİ")
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    private fun openAudioDiagnostics() {
        if (showingAudioDiagnostics) return

        voiceController?.onHostPaused()
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
    }
}
