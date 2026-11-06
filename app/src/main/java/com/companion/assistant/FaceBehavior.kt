package com.companion.assistant

import android.os.Handler
import android.os.SystemClock
import kotlin.random.Random

/** Event-driven character behavior; only the active eye transition requests animation frames. */
internal class FaceBehavior(
    private val handler: Handler,
    private val listener: Listener
) {
    interface Listener {
        fun setExpression(expression: FaceExpression, status: String, durationMs: Long)
        fun moveGaze(x: Float, y: Float, durationMs: Long)
        fun blink(durationMs: Long)
    }

    private var running = false
    private var hasWelcomed = false
    private var touchHeld = false
    private var longPressTriggered = false
    private var lastInteraction = SystemClock.uptimeMillis()
    private var currentExpression = FaceExpression.NEUTRAL
    private var snoozeTriggered = false

    private val idleRunnable = Runnable { runIdleBehavior() }
    private val blinkRunnable = Runnable { runBlink() }
    private val returnRunnable = Runnable { returnToNeutral() }
    private val sleepyRunnable = Runnable { enterSleep() }
    private val wakeRunnable = Runnable { wakeFromSleep() }
    private val longPressRunnable = Runnable { enterTouchSleep() }

    fun start() {
        if (running) return
        running = true
        val now = SystemClock.uptimeMillis()
        lastInteraction = now

        if (!hasWelcomed) {
            hasWelcomed = true
            show(FaceExpression.ATTENTIVE, "HELLO, HUMAN", 720L)
            handler.postDelayed(returnRunnable, 2800L)
        } else if (currentExpression == FaceExpression.SLEEPING) {
            scheduleWake(18_000L)
        }

        scheduleIdle(6500L)
        scheduleBlink(3200L)
    }

    fun stop() {
        running = false
        touchHeld = false
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(blinkRunnable)
        handler.removeCallbacks(returnRunnable)
        handler.removeCallbacks(sleepyRunnable)
        handler.removeCallbacks(wakeRunnable)
        handler.removeCallbacks(longPressRunnable)
    }

    fun onTouchDown(x: Float, y: Float) {
        if (!running) return
        lastInteraction = SystemClock.uptimeMillis()
        snoozeTriggered = false
        touchHeld = true
        longPressTriggered = false
        cancelBehaviorTimers()

        val woke = currentExpression == FaceExpression.SLEEPING ||
            currentExpression == FaceExpression.SLEEPY
        show(
            FaceExpression.ATTENTIVE,
            if (woke) "OH! HELLO" else "I'M LISTENING",
            500L
        )
        listener.moveGaze(x, y, 300L)
        handler.postDelayed(longPressRunnable, 720L)
    }

    fun onTouchMove(x: Float, y: Float) {
        if (running && touchHeld && !longPressTriggered) {
            listener.moveGaze(x, y, 230L)
        }
    }

    fun onTouchUp(elapsedMs: Long) {
        if (!running) return
        touchHeld = false
        handler.removeCallbacks(longPressRunnable)

        if (longPressTriggered) {
            scheduleWake(14_000L)
        } else if (elapsedMs < 650L) {
            show(FaceExpression.HAPPY, "THAT'S FOR ME", 460L)
            handler.postDelayed(returnRunnable, 1500L)
            scheduleBlink(2600L)
        } else {
            show(FaceExpression.ATTENTIVE, "FOLLOWING YOU", 400L)
            handler.postDelayed(returnRunnable, 2000L)
        }

        scheduleIdle(5000L)
    }

    fun onTouchCancel() {
        if (!running) return
        touchHeld = false
        handler.removeCallbacks(longPressRunnable)
        scheduleIdle(5000L)
    }

    private fun runIdleBehavior() {
        if (!running || touchHeld) return

        if (currentExpression == FaceExpression.SLEEPING) {
            scheduleIdle(7000L)
            return
        }

        val idleFor = SystemClock.uptimeMillis() - lastInteraction
        if (idleFor > 105_000L && !snoozeTriggered && Random.nextFloat() < 0.38f) {
            snoozeTriggered = true
            show(FaceExpression.SLEEPY, "EYES GETTING HEAVY", 850L)
            handler.postDelayed(sleepyRunnable, 6200L)
            scheduleIdle(12_000L)
            return
        }

        when (Random.nextInt(100)) {
            in 0..21 -> {
                show(FaceExpression.NEUTRAL, "JUST LOOKING", 520L)
                listener.moveGaze(randomGazeX(), randomGazeY(), 760L)
            }
            in 22..54 -> {
                show(FaceExpression.CURIOUS, "WHAT'S THAT?", 620L)
                listener.moveGaze(randomSide(), randomGazeY(), 520L)
                handler.postDelayed(returnRunnable, 2600L)
            }
            in 55..72 -> {
                show(FaceExpression.HAPPY, "NICE TO SEE YOU", 520L)
                handler.postDelayed(returnRunnable, 2300L)
            }
            in 73..77 -> {
                show(FaceExpression.SURPRISED, "OH!", 420L)
                handler.postDelayed(returnRunnable, 1300L)
            }
            else -> {
                show(FaceExpression.NEUTRAL, "JUST RELAXING", 600L)
                listener.moveGaze(0f, 0f, 700L)
            }
        }

        scheduleIdle(Random.nextLong(5200L, 8800L))
    }

    private fun runBlink() {
        if (!running) return
        if (currentExpression != FaceExpression.SLEEPING) {
            listener.blink(Random.nextLong(170L, 235L))
            scheduleBlink(Random.nextLong(3000L, 6700L))
        }
    }

    private fun returnToNeutral() {
        if (!running || touchHeld) return
        if (currentExpression == FaceExpression.SLEEPING || currentExpression == FaceExpression.SLEEPY) return
        show(FaceExpression.NEUTRAL, "JUST RELAXING", 620L)
        if (Random.nextFloat() < 0.5f) {
            listener.moveGaze(randomGazeX() * 0.6f, randomGazeY() * 0.6f, 700L)
        }
    }

    private fun enterSleep() {
        if (!running || touchHeld || currentExpression != FaceExpression.SLEEPY) return
        show(FaceExpression.SLEEPING, "ZZZ", 1100L)
        handler.removeCallbacks(blinkRunnable)
        scheduleWake(Random.nextLong(18_000L, 28_000L))
    }

    private fun enterTouchSleep() {
        if (!running || !touchHeld) return
        longPressTriggered = true
        show(FaceExpression.SLEEPING, "SNOOZE MODE", 680L)
        handler.removeCallbacks(returnRunnable)
        handler.removeCallbacks(blinkRunnable)
    }

    private fun wakeFromSleep() {
        if (!running || touchHeld || currentExpression != FaceExpression.SLEEPING) return
        show(FaceExpression.NEUTRAL, "GOOD MORNING", 900L)
        listener.moveGaze(0f, 0f, 800L)
        scheduleBlink(1600L)
    }

    private fun show(expression: FaceExpression, status: String, durationMs: Long) {
        currentExpression = expression
        listener.setExpression(expression, status, durationMs)
        if (expression == FaceExpression.SLEEPING) {
            handler.removeCallbacks(blinkRunnable)
        }
    }

    private fun scheduleIdle(delayMs: Long) {
        handler.removeCallbacks(idleRunnable)
        if (running) handler.postDelayed(idleRunnable, delayMs)
    }

    private fun scheduleBlink(delayMs: Long) {
        handler.removeCallbacks(blinkRunnable)
        if (running && currentExpression != FaceExpression.SLEEPING) {
            handler.postDelayed(blinkRunnable, delayMs)
        }
    }

    private fun scheduleWake(delayMs: Long) {
        handler.removeCallbacks(wakeRunnable)
        if (running) handler.postDelayed(wakeRunnable, delayMs)
    }

    private fun cancelBehaviorTimers() {
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(returnRunnable)
        handler.removeCallbacks(sleepyRunnable)
        handler.removeCallbacks(wakeRunnable)
    }

    private fun randomSide(): Float = if (Random.nextBoolean()) 0.52f else -0.52f
    private fun randomGazeX(): Float = Random.nextFloat() * 1.0f - 0.5f
    private fun randomGazeY(): Float = Random.nextFloat() * 0.55f - 0.275f
}
