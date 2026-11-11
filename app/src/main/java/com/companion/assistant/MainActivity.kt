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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var faceView: FaceView? = null
    private var voiceController: VoiceInteractionController? = null
    private var microphonePermissionRequestPending = false
    private var robotCore: RobotCore? = null
    private var accessTokenStore: CompanionAccessTokenStore? = null
    private var memoryConsentStore: MemoryConsentStore? = null
    private var securityDialog: AlertDialog? = null
    private var memoryDialog: AlertDialog? = null
    private var memoryApiClient: MemoryApiClient? = null
    private val memoryExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-memory-io").apply { isDaemon = true }
    }

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
        memoryApiClient = MemoryApiClient(BuildConfig.COMPANION_BACKEND_URL, tokenStore::accessToken)
        core.dispatch(RobotEvent.PersistentMemoryConsentChanged(consentStore.isEnabled()))
        val speechInput = SpeechInputFactory.create(applicationContext)
        voiceController = VoiceInteractionController(
            robotCore = core,
            speechInput = speechInput.input,
            recordedSpeechRecognizer = speechInput.recordedSpeechRecognizer,
            speechOutput = TextToSpeechManager(applicationContext),
            conversationEngine = ConversationEngineFactory.create(
                endpoint = BuildConfig.COMPANION_BACKEND_URL,
                accessTokenProvider = tokenStore::accessToken,
                memoryConsentProvider = consentStore::isEnabled
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
        memoryDialog?.dismiss()
        memoryDialog = null
        dispatchRobotEvent(RobotEvent.CloseRequested)
        voiceController?.close()
        voiceController = null
        memoryApiClient?.cancelRequests()
        memoryExecutor.shutdownNow()
        memoryApiClient = null
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
            text = "Uzun süreli hafızayı etkinleştir"
            isChecked = consentStore.isEnabled()
            contentDescription = "Uzun süreli hafızayı etkinleştir"
        }
        val memoryDetails = TextView(this).apply {
            text = "Açıldığında seçilen anılar yerel backend'de saklanır ve yanıt için yapılandırılmış AI sağlayıcısına gönderilebilir. Kapatmak yeni kayıt ve AI kullanımını durdurur; mevcut kayıtları silmez. Son sekiz sohbet mesajı Android'de tutulur."
            setPadding(0, (4f * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val memoryManager = Button(this).apply {
            text = "HAFIZAYI YÖNET"
            contentDescription = "Kayıtlı anıları görüntüle, düzenle veya sil"
            setOnClickListener {
                securityDialog?.dismiss()
                showMemoryManagement()
            }
        }
        content.addView(details)
        content.addView(tokenInput)
        content.addView(memoryConsent)
        content.addView(memoryDetails)
        content.addView(memoryManager)

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

    private fun showMemoryManagement() {
        if (isFinishing) return
        val loading = AlertDialog.Builder(this)
            .setTitle("UZUN SÜRELİ HAFIZA")
            .setMessage("Kayıtlar yükleniyor…")
            .setNegativeButton("KAPAT", null)
            .create()
        memoryDialog = loading
        loading.setOnDismissListener { if (memoryDialog === loading) memoryDialog = null }
        loading.show()
        applyDialogImmersive(loading)
        memoryExecutor.execute {
            try {
                val client = memoryApiClient ?: throw MemoryApiFailure("BELLEK İSTEMCİSİ KAPALI")
                val status = client.status()
                val memories = client.list()
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    loading.dismiss()
                    displayMemoryList(status, memories)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    loading.dismiss()
                    showMemoryError(error)
                }
            }
        }
    }

    private fun displayMemoryList(status: MemoryStatus, memories: List<StoredMemory>) {
        if (isFinishing || memories.size > 100) return
        val density = resources.displayMetrics.density
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
        }
        val consentEnabled = memoryConsentStore?.isEnabled() == true
        rows.addView(TextView(this).apply {
            text = "Depo: ${if (status.available) "hazır" else "kullanılamıyor"} · ${status.activeCount} kayıt · " +
                if (consentEnabled) "hafıza açık" else "hafıza kapalı"
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        rows.addView(TextView(this).apply {
            text = "Hafızayı kapatmak kayıtları silmez. Kayıtları seçerek veya tümünü silerek kaldırabilirsin."
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        if (memories.isEmpty()) {
            rows.addView(TextView(this).apply { text = "Kayıtlı anı yok." })
        } else {
            memories.forEach { memory ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                }
                item.addView(TextView(this).apply {
                    text = "${memory.category.toTurkishMemoryCategory()} · ${memory.key.replace('_', ' ')}"
                    textSize = 15f
                })
                item.addView(TextView(this).apply {
                    text = memory.value
                    setPadding(0, (3 * density).toInt(), 0, (4 * density).toInt())
                })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(Button(this@MainActivity).apply {
                    text = "DÜZENLE"
                    setOnClickListener { showEditMemory(memory) }
                })
                actions.addView(Button(this@MainActivity).apply {
                    text = "SİL"
                    setOnClickListener { confirmDeleteMemory(memory) }
                })
                item.addView(actions)
                rows.addView(item)
            }
        }
        val refresh = Button(this).apply {
            text = "YENİLE"
            setOnClickListener {
                memoryDialog?.dismiss()
                showMemoryManagement()
            }
        }
        rows.addView(refresh)
        val scrollView = ScrollView(this).apply { addView(rows) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("KAYITLI ANILAR")
            .setView(scrollView)
            .setPositiveButton("KAPAT", null)
            .setNeutralButton("TÜMÜNÜ SİL", null)
            .create()
        memoryDialog = dialog
        dialog.setOnDismissListener { if (memoryDialog === dialog) memoryDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                confirmClearMemories()
            }
            applyDialogImmersive(dialog)
        }
        dialog.show()
    }

    private fun showEditMemory(memory: StoredMemory) {
        val input = EditText(this).apply {
            setText(memory.value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            minLines = 2
            maxLines = 5
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ANIYI DÜZENLE")
            .setMessage(memory.key.replace('_', ' '))
            .setView(input)
            .setPositiveButton("KAYDET", null)
            .setNegativeButton("VAZGEÇ", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isBlank()) {
                    Toast.makeText(this, "ANI BOŞ OLAMAZ", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                runMemoryOperation(
                    operation = { (memoryApiClient ?: throw MemoryApiFailure("BELLEK İSTEMCİSİ KAPALI")).updateValue(memory, value) },
                    successMessage = "ANI GÜNCELLENDİ"
                )
            }
            applyDialogImmersive(dialog)
        }
        dialog.show()
    }

    private fun confirmDeleteMemory(memory: StoredMemory) {
        AlertDialog.Builder(this)
            .setTitle("ANIYI SİL?")
            .setMessage("‘${memory.key.replace('_', ' ')}’ kaydı kalıcı olarak silinecek.")
            .setPositiveButton("SİL") { _, _ ->
                runMemoryOperation(
                    operation = { (memoryApiClient ?: throw MemoryApiFailure("BELLEK İSTEMCİSİ KAPALI")).delete(memory.id) },
                    successMessage = "ANI SİLİNDİ"
                )
            }
            .setNegativeButton("VAZGEÇ", null)
            .show()
    }

    private fun confirmClearMemories() {
        AlertDialog.Builder(this)
            .setTitle("TÜM ANILAR SİLİNSİN Mİ?")
            .setMessage("Tüm uzun süreli bellek kayıtları kalıcı olarak silinecek. Sohbet geçmişi ayrı kalır.")
            .setPositiveButton("TÜMÜNÜ SİL") { _, _ ->
                runMemoryOperation(
                    operation = { (memoryApiClient ?: throw MemoryApiFailure("BELLEK İSTEMCİSİ KAPALI")).clearAll() },
                    successMessage = "TÜM ANILAR SİLİNDİ"
                )
            }
            .setNegativeButton("VAZGEÇ", null)
            .show()
    }

    private fun runMemoryOperation(operation: () -> Unit, successMessage: String) {
        memoryExecutor.execute {
            try {
                operation()
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
                    memoryDialog?.dismiss()
                    showMemoryManagement()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    showMemoryError(error)
                }
            }
        }
    }

    private fun showMemoryError(error: Exception) {
        val message = (error as? MemoryApiFailure)?.message ?: "BELLEK İŞLEMİ TAMAMLANAMADI"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun applyDialogImmersive(dialog: AlertDialog) {
        dialog.window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun String.toTurkishMemoryCategory(): String = when (this) {
        "profile" -> "Profil"
        "preference" -> "Tercih"
        "fact" -> "Bilgi"
        "routine" -> "Rutin"
        "summary" -> "Özet"
        else -> this
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
