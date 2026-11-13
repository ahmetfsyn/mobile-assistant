package com.companion.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Pluggable voice output contract; cloud TTS can implement this alongside Android TTS. */
internal interface SpeechOutput : Closeable {
    interface Callback {
        fun onStarted()
        fun onCompleted()
        fun onError(message: String)
    }

    fun speak(text: String, language: VoiceLanguage, callback: Callback)
    fun speak(text: String, language: VoiceLanguage, delivery: VoiceDelivery, callback: Callback) {
        speak(text, language, callback)
    }
    fun stop()
}

internal object TtsAudioUsage {
    fun forSdk(sdk: Int): Int = if (sdk >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA
}

/** Native offline-capable Android TTS implementation. */
internal class TextToSpeechManager(context: Context) : SpeechOutput {
    private val voiceSelection = VoiceSelectionStore(context)
    private data class PendingSpeech(
        val text: String,
        val language: VoiceLanguage,
        val delivery: VoiceDelivery,
        val callback: SpeechOutput.Callback
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val utteranceSequence = AtomicLong()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initializationFinished = false
    private var closed = false
    private var pending: PendingSpeech? = null
    private var currentCallback: SpeechOutput.Callback? = null
    private var currentUtteranceId: String? = null
    private var audioFocusHeld = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            mainHandler.post { interruptForAudioFocusLoss() }
        }
    }

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post { onInitialized(status) }
        }
    }

    override fun speak(text: String, language: VoiceLanguage, callback: SpeechOutput.Callback) {
        speak(text, language, VoiceDelivery.DEFAULT, callback)
    }

    override fun speak(
        text: String,
        language: VoiceLanguage,
        delivery: VoiceDelivery,
        callback: SpeechOutput.Callback
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { speak(text, language, delivery, callback) }
            return
        }
        if (closed) {
            callback.onError("SES MOTORU KAPALI")
            return
        }
        if (text.isBlank()) {
            callback.onError("SÖYLENECEK BİR YANIT YOK")
            return
        }

        if (!ready) {
            if (initializationFinished) {
                callback.onError("KONUŞMA MOTORU BAŞLATILAMADI")
                return
            }
            pending = PendingSpeech(text, language, delivery.bounded(), callback)
            return
        }
        speakReady(text, language, delivery.bounded(), callback)
    }

    override fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stop() }
            return
        }
        pending = null
        currentCallback = null
        currentUtteranceId = null
        engine?.stop()
        abandonAudioFocus()
    }

    override fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { close() }
            return
        }
        if (closed) return
        closed = true
        pending = null
        currentCallback = null
        currentUtteranceId = null
        engine?.stop()
        abandonAudioFocus()
        engine?.shutdown()
        engine = null
    }

    private fun onInitialized(status: Int) {
        if (closed) {
            engine?.shutdown()
            engine = null
            return
        }
        val tts = engine ?: return
        initializationFinished = true
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            pending?.callback?.onError("KONUŞMA MOTORU BAŞLATILAMADI")
            pending = null
            return
        }

        tts.setSpeechRate(0.96f)
        tts.setPitch(1.03f)
        // USAGE_ASSISTANT was added in API 26; API 24–25 use the normal speaker media route.
        val usage = TtsAudioUsage.forSdk(Build.VERSION.SDK_INT)
        val attributesResult = tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        if (attributesResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "audio_attributes_rejected sdk=${Build.VERSION.SDK_INT} result=$attributesResult")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                dispatch(utteranceId) { it.onStarted() }
            }

            override fun onDone(utteranceId: String) {
                dispatch(utteranceId) {
                    currentCallback = null
                    currentUtteranceId = null
                    abandonAudioFocus()
                    it.onCompleted()
                }
            }

            override fun onError(utteranceId: String) {
                dispatchError(utteranceId)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                dispatchError(utteranceId)
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (!interrupted) dispatchError(utteranceId)
            }
        })
        ready = true
        val turkishVoices = tts.voices.orEmpty().filter { it.locale.language == "tr" }
        Log.i(TAG, "turkish_voice_inventory count=${turkishVoices.size} entries=${turkishVoices.joinToString { voice ->
            "${voice.name}:${voice.locale.toLanguageTag()}:network=${voice.isNetworkConnectionRequired}"
        }}")
        Log.i(TAG, "engine_ready sdk=${Build.VERSION.SDK_INT} usage=$usage")
        val queued = pending
        pending = null
        if (queued != null) speakReady(queued.text, queued.language, queued.delivery, queued.callback)
    }

    private fun speakReady(
        text: String,
        language: VoiceLanguage,
        delivery: VoiceDelivery,
        callback: SpeechOutput.Callback
    ) {
        val tts = engine ?: run {
            callback.onError("KONUŞMA MOTORU KULLANILAMIYOR")
            return
        }
        val languageStatus = tts.setLanguage(language.locale)
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            val message = if (language == VoiceLanguage.TURKISH) {
                "TÜRKÇE SES VERİSİ YÜKLÜ DEĞİL"
            } else {
                "İNGİLİZCE SES VERİSİ YÜKLÜ DEĞİL"
            }
            callback.onError(message)
            return
        }

        stop()
        val boundedDelivery = delivery.bounded()
        tts.setSpeechRate(boundedDelivery.speechRate)
        val voiceChoice = voiceSelection.selected()
        tts.setPitch((boundedDelivery.pitch * voiceChoice.pitchMultiplier).coerceIn(0.5f, 2.0f))
        val utteranceId = "companion-${utteranceSequence.incrementAndGet()}"
        currentCallback = callback
        currentUtteranceId = utteranceId
        if (!requestAudioFocus()) {
            currentCallback = null
            currentUtteranceId = null
            callback.onError("SES ODAĞI ALINAMADI")
            return
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) dispatchError(utteranceId)
    }

    private fun dispatch(utteranceId: String, action: (SpeechOutput.Callback) -> Unit) {
        mainHandler.post {
            if (closed || currentUtteranceId != utteranceId) return@post
            val callback = currentCallback ?: return@post
            action(callback)
        }
    }

    private fun dispatchError(utteranceId: String) {
        mainHandler.post {
            if (closed || currentUtteranceId != utteranceId) return@post
            val callback = currentCallback
            currentCallback = null
            currentUtteranceId = null
            abandonAudioFocus()
            callback?.onError("KONUŞMA TAMAMLANAMADI")
            Log.w(TAG, "utterance_failed id=$utteranceId")
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        val result = try {
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        } catch (_: RuntimeException) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return audioFocusHeld
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!audioFocusHeld) return
        audioFocusHeld = false
        try {
            audioManager.abandonAudioFocus(audioFocusListener)
        } catch (_: RuntimeException) {
            // Focus cleanup must not block voice-session or Activity teardown.
        }
    }

    private fun interruptForAudioFocusLoss() {
        val callback = currentCallback ?: return
        currentCallback = null
        currentUtteranceId = null
        engine?.stop()
        abandonAudioFocus()
        callback.onError("SES ODAĞI KESİLDİ")
    }

    private companion object {
        const val TAG = "CompanionTts"
    }
}
