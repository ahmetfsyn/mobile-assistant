package com.companion.assistant

/** Lightweight time-based animation state. All per-frame values are updated in place. */
internal class FaceAnimator {

    private class Values {
        var scaleX = 1f
        var scaleY = 1f
        var lid = 0f
        var gazeX = 0f
        var gazeY = 0f
        var tilt = 0f

        fun copyFrom(other: Values) {
            scaleX = other.scaleX
            scaleY = other.scaleY
            lid = other.lid
            gazeX = other.gazeX
            gazeY = other.gazeY
            tilt = other.tilt
        }
    }

    private val current = Values()
    private val from = Values()
    private val target = Values()

    var expression: FaceExpression = FaceExpression.NEUTRAL
        private set

    val eyeScaleX: Float get() = current.scaleX
    val eyeScaleY: Float get() = current.scaleY
    val lidClosure: Float get() = current.lid
    val gazeX: Float get() = current.gazeX
    val gazeY: Float get() = current.gazeY
    val tilt: Float get() = current.tilt
    val isSettledSleeping: Boolean
        get() = expression == FaceExpression.SLEEPING && !transitionActive
    var blinkAmount: Float = 0f
        private set

    private var transitionStart = 0L
    private var transitionDuration = 0L
    private var transitionActive = false
    private var blinkStart = -1L
    private var blinkDuration = 200L

    fun transitionTo(expression: FaceExpression, now: Long, durationMs: Long) {
        update(now)
        this.expression = expression
        target.scaleX = expression.eyeScaleX
        target.scaleY = expression.eyeScaleY
        target.lid = expression.lidClosure
        target.tilt = expression.tilt
        beginTransition(now, durationMs)
    }

    fun moveGaze(x: Float, y: Float, now: Long, durationMs: Long = 420L) {
        update(now)
        target.gazeX = x.coerceIn(-1f, 1f)
        target.gazeY = y.coerceIn(-1f, 1f)
        beginTransition(now, durationMs)
    }

    private fun beginTransition(now: Long, durationMs: Long) {
        from.copyFrom(current)
        transitionStart = now
        transitionDuration = durationMs.coerceAtLeast(1L)
        transitionActive = true
    }

    fun startBlink(now: Long, durationMs: Long) {
        if (expression == FaceExpression.SLEEPING) return
        blinkStart = now
        blinkDuration = durationMs.coerceAtLeast(120L)
    }

    fun update(now: Long) {
        if (transitionActive) {
            val raw = ((now - transitionStart).toFloat() / transitionDuration)
                .coerceIn(0f, 1f)
            val eased = raw * raw * (3f - 2f * raw)
            current.scaleX = lerp(from.scaleX, target.scaleX, eased)
            current.scaleY = lerp(from.scaleY, target.scaleY, eased)
            current.lid = lerp(from.lid, target.lid, eased)
            current.gazeX = lerp(from.gazeX, target.gazeX, eased)
            current.gazeY = lerp(from.gazeY, target.gazeY, eased)
            current.tilt = lerp(from.tilt, target.tilt, eased)

            if (raw >= 1f) {
                current.copyFrom(target)
                transitionActive = false
            }
        }

        if (blinkStart >= 0L) {
            val progress = ((now - blinkStart).toFloat() / blinkDuration)
                .coerceIn(0f, 1f)
            val phase = if (progress < 0.46f) {
                progress / 0.46f
            } else {
                1f - ((progress - 0.46f) / 0.54f)
            }.coerceIn(0f, 1f)
            blinkAmount = phase * phase * (3f - 2f * phase)
            if (progress >= 1f) {
                blinkStart = -1L
                blinkAmount = 0f
            }
        } else {
            blinkAmount = 0f
        }
    }

    fun isAnimating(now: Long): Boolean {
        update(now)
        return transitionActive || blinkStart >= 0L || expression == FaceExpression.SLEEPING
    }

    fun breathing(now: Long): Float {
        if (expression != FaceExpression.SLEEPING) return 0f
        return (0.5f + 0.5f * kotlin.math.sin(now * 0.0016)).toFloat()
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount
}
