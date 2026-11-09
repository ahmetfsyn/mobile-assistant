package com.companion.assistant

/** Existing eye expression plus a short Turkish status label selected from trusted local state. */
internal data class FaceCue(val expression: FaceExpression, val status: String)

/** Deterministic mapping: model-generated response text is never an input. */
internal object FaceCueMapper {
    fun map(emotion: EmotionState): FaceCue = when {
        emotion.valence >= HAPPY_VALENCE && emotion.engagement >= ENGAGED ->
            FaceCue(FaceExpression.HAPPY, "NEŞELİ BAKIŞ")
        emotion.curiosity >= CURIOUS && emotion.engagement >= ENGAGED ->
            FaceCue(FaceExpression.CURIOUS, "MERAKLI BAKIŞ")
        emotion.arousal < CALM_AROUSAL && emotion.energy < CALM_ENERGY ->
            FaceCue(FaceExpression.NEUTRAL, "SAKİN MOD")
        else -> FaceCue(FaceExpression.NEUTRAL, "HAZIRIM")
    }

    private const val HAPPY_VALENCE = 0.63f
    private const val CURIOUS = 0.40f
    private const val ENGAGED = 0.42f
    private const val CALM_AROUSAL = 0.30f
    private const val CALM_ENERGY = 0.30f
}
