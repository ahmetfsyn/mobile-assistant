package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityEmotionTest {
    private val reducer = EmotionReducer()

    @Test
    fun trustedEventsStayWithinNormalizedBounds() {
        var state = EmotionState.CALM
        repeat(100) { index ->
            state = reducer.reduce(state, EmotionEvent.RESPONSE_RECEIVED, index * 100L)
            assertInUnitRange(state.valence)
            assertInUnitRange(state.arousal)
            assertInUnitRange(state.curiosity)
            assertInUnitRange(state.energy)
            assertInUnitRange(state.engagement)
        }
    }

    @Test
    fun sameEmotionSnapshotAlwaysProducesTheSameExistingFaceCue() {
        val interested = EmotionState(
            valence = 0.55f,
            arousal = 0.52f,
            curiosity = 0.71f,
            energy = 0.48f,
            engagement = 0.68f,
            updatedAtMs = 100L
        )

        val first = FaceCueMapper.map(interested)
        val second = FaceCueMapper.map(interested)

        assertEquals(first, second)
        assertEquals(FaceExpression.CURIOUS, first.expression)
    }

    @Test
    fun decayMovesMoodExactlyHalfwayTowardCalmAtOneHalfLife() {
        val active = reducer.reduce(EmotionState.CALM, EmotionEvent.RESPONSE_RECEIVED, 1_000L)

        val decayed = reducer.decay(active, 19_000L)

        assertEquals((active.valence + EmotionState.BASELINE_VALENCE) / 2f, decayed.valence, 0.0001f)
        assertEquals((active.curiosity + EmotionState.BASELINE_CURIOSITY) / 2f, decayed.curiosity, 0.0001f)
        assertEquals(19_000L, decayed.updatedAtMs)
        assertTrue(kotlin.math.abs(decayed.valence - EmotionState.BASELINE_VALENCE) <
            kotlin.math.abs(active.valence - EmotionState.BASELINE_VALENCE))
    }

    @Test
    fun voiceDeliveryIsCappedToAComfortableRange() {
        val profile = PersonalityProfile.DEFAULT
        val highlyAnimated = EmotionState(
            valence = 1f,
            arousal = 1f,
            curiosity = 1f,
            energy = 1f,
            engagement = 1f
        )

        val delivery = profile.voiceDeliveryFor(highlyAnimated).bounded()

        assertInRange(delivery.speechRate, VoiceDelivery.MIN_SPEECH_RATE, VoiceDelivery.MAX_SPEECH_RATE)
        assertInRange(delivery.pitch, VoiceDelivery.MIN_PITCH, VoiceDelivery.MAX_PITCH)
    }

    @Test
    fun maliciouslyOutOfRangeVectorIsClampedBeforeDelivery() {
        val profile = PersonalityProfile.DEFAULT
        val invalid = EmotionState(valence = 8f, arousal = -2f, curiosity = 12f, energy = 5f, engagement = -9f)

        val delivery = profile.voiceDeliveryFor(invalid)

        assertInRange(delivery.speechRate, VoiceDelivery.MIN_SPEECH_RATE, VoiceDelivery.MAX_SPEECH_RATE)
        assertInRange(delivery.pitch, VoiceDelivery.MIN_PITCH, VoiceDelivery.MAX_PITCH)
    }

    private fun assertInUnitRange(value: Float) = assertInRange(value, 0f, 1f)

    private fun assertInRange(value: Float, minimum: Float, maximum: Float) {
        assertTrue("$value is below $minimum", value >= minimum)
        assertTrue("$value is above $maximum", value <= maximum)
    }
}
