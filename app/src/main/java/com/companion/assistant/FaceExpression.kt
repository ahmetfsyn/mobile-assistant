package com.companion.assistant

/** The small set of moods the companion can express with its eyes. */
enum class FaceExpression(
    val eyeScaleX: Float,
    val eyeScaleY: Float,
    val lidClosure: Float,
    val tilt: Float
) {
    NEUTRAL(1f, 1f, 0f, 0f),
    CURIOUS(1.04f, 1.11f, 0.02f, -0.018f),
    SURPRISED(1.03f, 1.29f, 0f, 0f),
    HAPPY(1.02f, 1f, 0.29f, 0f),
    SLEEPY(1f, 1f, 0.57f, 0.012f),
    SLEEPING(0.99f, 1f, 1f, 0f),
    ATTENTIVE(1.01f, 1.04f, 0f, 0f)
}
