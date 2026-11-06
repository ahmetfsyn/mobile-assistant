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

    /** Empty endpoint selects the development mock. Never place a production API key here. */
    @Volatile
    var cloudSpeechEndpoint: String = ""

    @Volatile
    var cloudSpeechBearerToken: String = ""

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
