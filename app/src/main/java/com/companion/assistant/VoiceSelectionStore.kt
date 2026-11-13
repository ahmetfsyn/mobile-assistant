package com.companion.assistant

import android.content.Context

/** Android's Voice metadata has no gender field; the lower-pitch option is explicitly approximate. */
internal enum class VoiceChoice(val label: String, val pitchMultiplier: Float) {
    DEFAULT_TURKISH("Türkçe varsayılan ses", 1.0f),
    LOWER_PITCH("Erkek benzeri ses (yaklaşık)", 0.78f)
}

internal class VoiceSelectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_selection", Context.MODE_PRIVATE)

    fun selected(): VoiceChoice = runCatching {
        VoiceChoice.valueOf(prefs.getString("selected_voice", VoiceChoice.DEFAULT_TURKISH.name).orEmpty())
    }.getOrDefault(VoiceChoice.DEFAULT_TURKISH)

    fun select(choice: VoiceChoice) {
        prefs.edit().putString("selected_voice", choice.name).apply()
    }
}
