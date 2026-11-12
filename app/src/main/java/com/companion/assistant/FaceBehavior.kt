package com.companion.assistant

import android.os.Handler
import android.os.SystemClock
import kotlin.random.Random

/** Local eye behavior. RobotCore remains the authority for sleep and interaction states. */
internal class FaceBehavior(
    private val handler: Handler,
    private val listener: Listener
) {
    interface Listener {
        fun setExpression(expression: FaceExpression, status: String, durationMs: Long)
        fun moveGaze(x: Float, y: Float, durationMs: Long)
        fun blink(durationMs: Long)
        fun requestSleep()
    }

    private var running = false
    private var touchHeld = false
    private var longPressTriggered = false
    private var activity = RobotActivityState.BOOTING
    private var lastInteraction = SystemClock.uptimeMillis()
    private var currentExpression = FaceExpression.NEUTRAL
    private var snoozeTriggered = false
    private var preferredEmotionCue = FaceCueMapper.map(EmotionState.CALM)

    private val idleRunnable = Runnable { runIdleBehavior() }
    private val blinkRunnable = Runnable { runBlink() }
    private val returnRunnable = Runnable { returnToNeutral() }
    private val sleepyRunnable = Runnable { requestSleepFromIdle() }
    private val longPressRunnable = Runnable { requestSleepFromTouch() }

    fun start() {
        if (running) return
        running = true
        lastInteraction = SystemClock.uptimeMillis()
        if (activity == RobotActivityState.IDLE) scheduleIdle(6500L)
        if (activity == RobotActivityState.IDLE) scheduleBlink(3200L)
    }

    fun stop() {
        running = false
        touchHeld = false
        cancelBehaviorTimers()
    }

    /** Every non-idle face is a direct rendering of the Core snapshot; no local wake timer exists. */
    fun setActivityState(next: RobotActivityState, errorMessage: String?) {
        val changed = activity != next
        activity = next
        if (!changed) return

        cancelBehaviorTimers()
        touchHeld = false
        longPressTriggered = false

        if (next == RobotActivityState.IDLE) {
            lastInteraction = SystemClock.uptimeMillis()
            snoozeTriggered = false
            show(preferredEmotionCue.expression, preferredEmotionCue.status, 560L)
            if (running) {
                scheduleIdle(6500L)
                scheduleBlink(3200L)
            }
            return
        }

        val presentation = RobotFacePresenter.forActivity(next, errorMessage)
        show(presentation.expression, presentation.status, presentation.durationMs)
        if (next == RobotActivityState.WAKING) {
            listener.moveGaze(0f, 0f, RobotFaceTransitionTiming.WAKE_ANIMATION_MS)
        }
    }

    fun onTouchDown(x: Float, y: Float) {
        if (!running) return
        touchHeld = true
        longPressTriggered = false
        lastInteraction = SystemClock.uptimeMillis()
        if (activity == RobotActivityState.IDLE) {
            cancelBehaviorTimers()
            listener.moveGaze(x, y, 300L)
            handler.postDelayed(longPressRunnable, 720L)
        }
    }

    fun onTouchMove(x: Float, y: Float) {
        if (running && touchHeld && activity == RobotActivityState.IDLE && !longPressTriggered) {
            listener.moveGaze(x, y, 230L)
        }
    }

    fun onTouchUp(elapsedMs: Long) {
        if (!running) return
        touchHeld = false
        handler.removeCallbacks(longPressRunnable)
        if (longPressTriggered) return

        if (activity == RobotActivityState.IDLE) {
            if (elapsedMs < 650L) show(FaceExpression.HAPPY, "BURADAYIM", 320L)
            else show(preferredEmotionCue.expression, preferredEmotionCue.status, 360L)
            scheduleIdle(1600L)
            scheduleBlink(2400L)
        }
    }

    fun onTouchCancel() {
        if (!running) return
        touchHeld = false
        handler.removeCallbacks(longPressRunnable)
        if (activity == RobotActivityState.IDLE) scheduleIdle(1200L)
    }

    /** Emotion is a local cue and cannot override any active RobotCore state. */
    fun setEmotionCue(cue: FaceCue) {
        preferredEmotionCue = cue
        if (activity == RobotActivityState.IDLE && running && !touchHeld) {
            show(cue.expression, cue.status, 560L)
        }
    }

    private fun runIdleBehavior() {
        if (!running || touchHeld || activity != RobotActivityState.IDLE) return

        val idleFor = SystemClock.uptimeMillis() - lastInteraction
        if (idleFor > 105_000L && !snoozeTriggered && Random.nextFloat() < 0.38f) {
            snoozeTriggered = true
            show(FaceExpression.SLEEPY, "GÖZLERİM AĞIRLAŞTI", 850L)
            handler.postDelayed(sleepyRunnable, 6200L)
            scheduleIdle(12_000L)
            return
        }

        when (Random.nextInt(100)) {
            in 0..21 -> {
                show(FaceExpression.NEUTRAL, "ETRAFI İZLİYORUM", 520L)
                listener.moveGaze(randomGazeX(), randomGazeY(), 760L)
            }
            in 22..54 -> {
                show(FaceExpression.CURIOUS, "BU DA NE?", 620L)
                listener.moveGaze(randomSide(), randomGazeY(), 520L)
                handler.postDelayed(returnRunnable, 2600L)
            }
            in 55..72 -> {
                show(FaceExpression.HAPPY, "SENİ GÖRMEK GÜZEL", 520L)
                handler.postDelayed(returnRunnable, 2300L)
            }
            in 73..77 -> {
                show(FaceExpression.SURPRISED, "OO!", 420L)
                handler.postDelayed(returnRunnable, 1300L)
            }
            else -> {
                show(FaceExpression.NEUTRAL, "BURADAYIM", 600L)
                listener.moveGaze(0f, 0f, 700L)
            }
        }

        scheduleIdle(Random.nextLong(5200L, 8800L))
    }

    private fun runBlink() {
        if (!running || activity != RobotActivityState.IDLE) return
        if (currentExpression != FaceExpression.SLEEPING) {
            listener.blink(Random.nextLong(170L, 235L))
            scheduleBlink(Random.nextLong(3000L, 6700L))
        }
    }

    private fun returnToNeutral() {
        if (!running || touchHeld || activity != RobotActivityState.IDLE) return
        if (currentExpression == FaceExpression.SLEEPING || currentExpression == FaceExpression.SLEEPY) return
        show(preferredEmotionCue.expression, preferredEmotionCue.status, 620L)
        if (Random.nextFloat() < 0.5f) {
            listener.moveGaze(randomGazeX() * 0.6f, randomGazeY() * 0.6f, 700L)
        }
    }

    private fun requestSleepFromIdle() {
        if (!running || activity != RobotActivityState.IDLE || currentExpression != FaceExpression.SLEEPY) return
        listener.requestSleep()
    }

    private fun requestSleepFromTouch() {
        if (!running || !touchHeld || activity != RobotActivityState.IDLE) return
        longPressTriggered = true
        listener.requestSleep()
    }

    private fun show(expression: FaceExpression, status: String, durationMs: Long) {
        currentExpression = expression
        listener.setExpression(expression, status, durationMs)
    }

    private fun scheduleIdle(delayMs: Long) {
        handler.removeCallbacks(idleRunnable)
        if (running && activity == RobotActivityState.IDLE) handler.postDelayed(idleRunnable, delayMs)
    }

    private fun scheduleBlink(delayMs: Long) {
        handler.removeCallbacks(blinkRunnable)
        if (running && activity == RobotActivityState.IDLE && currentExpression != FaceExpression.SLEEPING) {
            handler.postDelayed(blinkRunnable, delayMs)
        }
    }

    private fun cancelBehaviorTimers() {
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(blinkRunnable)
        handler.removeCallbacks(returnRunnable)
        handler.removeCallbacks(sleepyRunnable)
        handler.removeCallbacks(longPressRunnable)
    }

    private fun randomSide(): Float = if (Random.nextBoolean()) 0.52f else -0.52f
    private fun randomGazeX(): Float = Random.nextFloat() * 1.0f - 0.5f
    private fun randomGazeY(): Float = Random.nextFloat() * 0.55f - 0.275f
}
