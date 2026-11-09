package com.companion.assistant

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var faceView: FaceView? = null
    private var voiceController: VoiceInteractionController? = null
    private var microphonePermissionRequestPending = false
    private var robotCore: RobotCore? = null
    private var accessTokenStore: CompanionAccessTokenStore? = null
    private var memoryConsentStore: MemoryConsentStore? = null
    private var securityDialog: AlertDialog? = null

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
        val tokenStore = CompanionAccessTokenStore(applicationContext)
        accessTokenStore = tokenStore
        val consentStore = MemoryConsentStore(applicationContext)
        memoryConsentStore = consentStore
        core.dispatch(RobotEvent.PersistentMemoryConsentChanged(consentStore.isEnabled()))
        val speechInput = SpeechInputFactory.create(applicationContext)
        voiceController = VoiceInteractionController(
            robotCore = core,
            speechInput = speechInput.input,
            recordedSpeechRecognizer = speechInput.recordedSpeechRecognizer,
            speechOutput = TextToSpeechManager(applicationContext),
            conversationEngine = ConversationEngineFactory.create(
                endpoint = BuildConfig.COMPANION_BACKEND_URL,
                accessTokenProvider = tokenStore::accessToken
            ),
            conversationHistory = SharedPreferencesConversationHistoryStore(applicationContext),
            onCaptionChanged = face::setCaption,
            onFaceCueChanged = face::setEmotionCue,
            onAuthenticationRequired = { showSecuritySettings(authenticationRequired = true) }
        )
        face.setOnFaceTapListener { onFaceTapped() }
        face.setOnSettingsTapListener { showSecuritySettings(authenticationRequired = false) }
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
        faceView?.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onPause() {
        dispatchRobotEvent(RobotEvent.HostPaused)
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
        microphonePermissionRequestPending = false

        dispatchRobotEvent(
            RobotEvent.MicrophonePermissionChanged(
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            )
        )
    }

    override fun onDestroy() {
        securityDialog?.dismiss()
        securityDialog = null
        dispatchRobotEvent(RobotEvent.CloseRequested)
        voiceController?.close()
        voiceController = null
        mainHandler.removeCallbacksAndMessages(null)
        robotCore = null
        faceView?.destroy()
        faceView = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        showSecuritySettings(authenticationRequired = false)
    }

    private fun onFaceTapped() {
        dispatchRobotEvent(RobotEvent.FaceTapped)
    }

    private fun showSecuritySettings(authenticationRequired: Boolean) {
        if (isFinishing || securityDialog?.isShowing == true) return
        val tokenStore = accessTokenStore ?: return
        val consentStore = memoryConsentStore ?: return
        val tokenIsStored = tokenStore.accessToken().isNotEmpty()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20f * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val details = TextView(this).apply {
            text = buildString {
                if (authenticationRequired) {
                    append("Sunucu erişim anahtarı istiyor. Backend'deki COMPANION_ACCESS_TOKEN ile aynı değeri girin. ")
                } else {
                    append("İsteğe bağlı yerel backend anahtarı ve gelecekteki bellek tercihini yönetin. ")
                }
                append("Bu tercih konuşma içeriğini veya Gemini anahtarını uygulamaya eklemez.")
            }
            setPadding(0, 0, 0, (12f * resources.displayMetrics.density).toInt())
        }
        val tokenInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            hint = if (tokenIsStored) "Boş bırakın: kayıtlı anahtar korunur" else "Backend erişim anahtarı"
            contentDescription = "Backend erişim anahtarı"
        }
        val memoryConsent = CheckBox(this).apply {
            text = "Gelecekteki uzun süreli bellek için rıza"
            isChecked = consentStore.isEnabled()
            contentDescription = "Gelecekteki uzun süreli bellek için rıza"
        }
        val memoryDetails = TextView(this).apply {
            text = "Uzun süreli bellek bu sürümde etkin değil ve yeni anılar saklanmıyor. Bu tercih gelecekteki özellik için kaydedilir. Mevcut son sekiz mesaj sohbet bağlamı için Android'de tutulur."
            setPadding(0, (4f * resources.displayMetrics.density).toInt(), 0, 0)
        }
        content.addView(details)
        content.addView(tokenInput)
        content.addView(memoryConsent)
        content.addView(memoryDetails)

        val dialog = AlertDialog.Builder(this)
            .setTitle("GÜVENLİK VE GİZLİLİK")
            .setView(content)
            .setPositiveButton("KAYDET", null)
            .setNeutralButton("ANAHTARI SİL", null)
            .setNegativeButton("KAPAT", null)
            .create()
        securityDialog = dialog
        dialog.setOnDismissListener {
            if (securityDialog === dialog) securityDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enteredToken = tokenInput.text?.toString().orEmpty().trim()
                if (enteredToken.isNotEmpty() && !tokenStore.saveAccessToken(enteredToken)) {
                    Toast.makeText(this, "ANAHTAR EN AZ 32 KARAKTER OLMALI", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                consentStore.setEnabled(memoryConsent.isChecked)
                robotCore?.dispatch(RobotEvent.PersistentMemoryConsentChanged(memoryConsent.isChecked))
                if (robotCore?.snapshot?.activity == RobotActivityState.ERROR) {
                    dispatchRobotEvent(RobotEvent.ErrorDismissed(robotCore?.snapshot?.sessionId ?: 0L))
                }
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                tokenStore.clearAccessToken()
                Toast.makeText(this, "ERİŞİM ANAHTARI SİLİNDİ", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        dialog.show()
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
                faceView?.setCaption(null)
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
