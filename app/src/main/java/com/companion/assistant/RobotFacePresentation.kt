package com.companion.assistant

/** Visual-only mapping for the authoritative RobotCore activity state. */
internal data class RobotFacePresentation(
    val expression: FaceExpression,
    val status: String,
    val durationMs: Long
)

internal object RobotFacePresenter {
    fun forActivity(activity: RobotActivityState, errorMessage: String? = null): RobotFacePresentation =
        when (activity) {
            RobotActivityState.BOOTING -> RobotFacePresentation(FaceExpression.NEUTRAL, "BAŞLIYORUM", 0L)
            RobotActivityState.IDLE -> RobotFacePresentation(FaceExpression.NEUTRAL, "BURADAYIM", 560L)
            RobotActivityState.WAKING -> RobotFacePresentation(
                FaceExpression.ATTENTIVE,
                "UYANIYORUM",
                RobotFaceTransitionTiming.WAKE_ANIMATION_MS
            )
            RobotActivityState.LISTENING -> RobotFacePresentation(FaceExpression.ATTENTIVE, "DİNLİYORUM", 320L)
            RobotActivityState.THINKING -> RobotFacePresentation(FaceExpression.CURIOUS, "DÜŞÜNÜYORUM", 380L)
            RobotActivityState.SPEAKING -> RobotFacePresentation(FaceExpression.HAPPY, "KONUŞUYORUM", 380L)
            RobotActivityState.INTERRUPTED -> RobotFacePresentation(FaceExpression.ATTENTIVE, "SÖZÜNÜ DİNLİYORUM", 180L)
            RobotActivityState.FOLLOW_UP_LISTENING -> RobotFacePresentation(FaceExpression.ATTENTIVE, "DEVAM EDEBİLİRSİN", 320L)
            RobotActivityState.SESSION_TIMEOUT -> RobotFacePresentation(FaceExpression.SLEEPY, "SOHBET BİTİYOR", 360L)
            RobotActivityState.AWAKE_GRACE -> RobotFacePresentation(FaceExpression.ATTENTIVE, "BURADAYIM", 420L)
            RobotActivityState.PROACTIVE_SPEAKING -> RobotFacePresentation(
                FaceExpression.HAPPY,
                "HATIRLATMA SÖYLÜYORUM",
                420L
            )
            RobotActivityState.RETURNING_TO_SLEEP -> RobotFacePresentation(
                FaceExpression.SLEEPING,
                "UYKUYA DÖNÜYORUM",
                RobotFaceTransitionTiming.SLEEP_ANIMATION_MS
            )
            RobotActivityState.SLEEPING -> RobotFacePresentation(FaceExpression.SLEEPING, "UYUYORUM", 480L)
            RobotActivityState.OBSERVING -> RobotFacePresentation(FaceExpression.ATTENTIVE, "İNCELEMEDE", 360L)
            RobotActivityState.ACTING -> RobotFacePresentation(FaceExpression.ATTENTIVE, "İŞLİYORUM", 360L)
            RobotActivityState.ERROR -> RobotFacePresentation(
                FaceExpression.CURIOUS,
                errorMessage ?: "KONUŞMA TAMAMLANAMADI",
                360L
            )
            RobotActivityState.CLOSED -> RobotFacePresentation(FaceExpression.SLEEPING, "", 0L)
        }
}
