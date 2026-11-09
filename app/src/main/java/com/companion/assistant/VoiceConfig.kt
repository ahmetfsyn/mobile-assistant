package com.companion.assistant

import java.util.Locale

/** App-level voice defaults. Turkish remains selected until the app changes this value explicitly. */
internal enum class VoiceLanguage(val tag: String, val locale: Locale) {
    TURKISH("tr-TR", Locale("tr", "TR")),
    ENGLISH("en-US", Locale.US)
}

internal object VoiceConfig {
    @Volatile
    var language: VoiceLanguage = VoiceLanguage.TURKISH

    /** Optional HTTPS PCM recognizer. Leave blank to use Android's installed speech service. */
    @Volatile
    var cloudSpeechEndpoint: String = ""

    @Volatile
    var cloudSpeechBearerToken: String = ""

    /** Development-only override. Normal builds use the installed Android recognizer by default. */
    @Volatile
    var useMockSpeechRecognition: Boolean = false

    /** Android defines true as offline-only; enable only after confirming an installed tr-TR model. */
    @Volatile
    var preferOfflineSpeechRecognition: Boolean = false

    @Volatile
    var mockTurkishTranscription: String = "Merhaba, nasılsın?"

    @Volatile
    var mockEnglishTranscription: String = "Hello, how are you?"

    @Volatile
    var turkishTestResponse: String = "Merhaba. Seni duymak güzel."

    @Volatile
    var englishTestResponse: String = "Hello. It's nice to hear from you."

    fun mockTranscription(language: VoiceLanguage): String = when (language) {
        VoiceLanguage.TURKISH -> mockTurkishTranscription
        VoiceLanguage.ENGLISH -> mockEnglishTranscription
    }

    fun testResponse(language: VoiceLanguage): String = when (language) {
        VoiceLanguage.TURKISH -> turkishTestResponse
        VoiceLanguage.ENGLISH -> englishTestResponse
    }
}
