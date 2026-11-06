package com.companion.assistant

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    private var faceView: FaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        faceView = FaceView(this)
        setContentView(faceView)
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
        faceView?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        faceView?.destroy()
        faceView = null
        super.onDestroy()
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
}
