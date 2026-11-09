package com.companion.assistant

/** Small, versioned presentation profile; it changes delivery style, never policy or permissions. */
internal data class PersonalityProfile(
    val id: String,
    val version: Int,
    val displayName: String,
    val defaultLanguageTag: String = "tr-TR",
    val traits: List<String> = listOf("sıcak", "meraklı", "oyuncu", "dürüst"),
    val defaultSpeechRate: Float = 0.96f,
    val defaultPitch: Float = 1.03f
) {
    fun voiceDeliveryFor(emotion: EmotionState): VoiceDelivery {
        val normalized = emotion.bounded()
        val rate = defaultSpeechRate + (normalized.arousal - EmotionState.BASELINE_AROUSAL) * 0.045f +
            (normalized.engagement - EmotionState.BASELINE_ENGAGEMENT) * 0.018f
        val pitch = defaultPitch + (normalized.valence - EmotionState.BASELINE_VALENCE) * 0.055f +
            (normalized.curiosity - EmotionState.BASELINE_CURIOSITY) * 0.025f
        return VoiceDelivery(rate, pitch).bounded()
    }

    private fun EmotionState.bounded() = copy(
        valence = valence.coerceIn(0f, 1f),
        arousal = arousal.coerceIn(0f, 1f),
        curiosity = curiosity.coerceIn(0f, 1f),
        energy = energy.coerceIn(0f, 1f),
        engagement = engagement.coerceIn(0f, 1f)
    )

    companion object {
        val DEFAULT = PersonalityProfile(
            id = "masa-arkadasi",
            version = 1,
            displayName = "Masa Arkadaşı",
            defaultLanguageTag = "tr-TR"
        )
    }
}

/** Speech delivery is intentionally subtle and clamped again at the platform TTS boundary. */
internal data class VoiceDelivery(val speechRate: Float, val pitch: Float) {
    fun bounded() = VoiceDelivery(
        speechRate = speechRate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE),
        pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
    )

    companion object {
        const val MIN_SPEECH_RATE = 0.92f
        const val MAX_SPEECH_RATE = 1.03f
        const val MIN_PITCH = 0.97f
        const val MAX_PITCH = 1.08f
        val DEFAULT = VoiceDelivery(0.96f, 1.03f)
    }
}
