package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAnimatorTest {
    @Test
    fun sleepingTransitionClosesEyesSmoothlyAndSettles() {
        val animator = FaceAnimator()

        animator.transitionTo(FaceExpression.SLEEPING, now = 0L, durationMs = 980L)
        animator.update(0L)
        assertEquals(0f, animator.lidClosure, 0.001f)

        animator.update(490L)
        assertTrue(animator.lidClosure > 0f)
        assertTrue(animator.lidClosure < 1f)

        animator.update(980L)
        assertEquals(1f, animator.lidClosure, 0.001f)
        assertTrue(animator.isSettledSleeping)
    }

    @Test
    fun wakeTransitionOpensSleepingEyesSmoothly() {
        val animator = FaceAnimator()
        animator.transitionTo(FaceExpression.SLEEPING, now = 0L, durationMs = 980L)
        animator.update(980L)

        animator.transitionTo(FaceExpression.ATTENTIVE, now = 1000L, durationMs = 720L)
        animator.update(1360L)
        assertTrue(animator.lidClosure > 0f)
        assertTrue(animator.lidClosure < 1f)

        animator.update(1720L)
        assertEquals(FaceExpression.ATTENTIVE, animator.expression)
        assertEquals(0f, animator.lidClosure, 0.001f)
        assertTrue(!animator.isSettledSleeping)
    }
}
