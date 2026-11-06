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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        val face = FaceView(this)
        faceView = face
        voiceController = VoiceInteractionController(
            faceView = face,
            speechRecognizer = SpeechRecognizerFactory.create(),
            speechOutput = TextToSpeechManager(applicationContext)
        )
        face.setOnFaceTapListener { onFaceTapped() }
        setContentView(face)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        faceView?.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onPause() {
        voiceController?.onHostPaused()
        faceView?.stop()
        super.onPause()
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
        voiceController?.close()
        voiceController = null
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
