package com.companion.assistant

import kotlin.math.pow

/** Trusted local events that can influence the companion's short-lived mood. */
internal enum class EmotionEvent {
    INTERACTION_STARTED,
    TRANSCRIPT_RECEIVED,
    RESPONSE_RECEIVED,
    INTERACTION_FAILED,
    INTERACTION_FINISHED
}

/** A transient local tone vector. Values are normalized to [0, 1]. */
internal data class EmotionState(
    val valence: Float = BASELINE_VALENCE,
    val arousal: Float = BASELINE_AROUSAL,
    val curiosity: Float = BASELINE_CURIOSITY,
    val energy: Float = BASELINE_ENERGY,
    val engagement: Float = BASELINE_ENGAGEMENT,
    val updatedAtMs: Long = 0L
) {
    companion object {
        const val BASELINE_VALENCE = 0.50f
        const val BASELINE_AROUSAL = 0.42f
        const val BASELINE_CURIOSITY = 0.25f
        const val BASELINE_ENERGY = 0.42f
        const val BASELINE_ENGAGEMENT = 0.25f

        val CALM = EmotionState()
    }
}

/** Pure reducer with bounded event influence and lazy exponential decay toward a calm baseline. */
internal class EmotionReducer(private val halfLifeMs: Long = DEFAULT_HALF_LIFE_MS) {
    init {
        require(halfLifeMs > 0L)
    }

    fun reduce(previous: EmotionState, event: EmotionEvent, nowMs: Long): EmotionState {
        val decayed = decay(previous, nowMs)
        val deltas = when (event) {
            EmotionEvent.INTERACTION_STARTED -> Deltas(valence = 0.035f, arousal = 0.045f, energy = 0.025f, engagement = 0.20f)
            EmotionEvent.TRANSCRIPT_RECEIVED -> Deltas(arousal = 0.025f, curiosity = 0.16f, engagement = 0.08f)
            EmotionEvent.RESPONSE_RECEIVED -> Deltas(valence = 0.18f, arousal = 0.015f, curiosity = 0.035f, energy = 0.035f, engagement = 0.06f)
            EmotionEvent.INTERACTION_FAILED -> Deltas(valence = -0.12f, arousal = 0.025f, curiosity = 0.06f, engagement = -0.08f)
            EmotionEvent.INTERACTION_FINISHED -> Deltas(arousal = -0.025f, energy = -0.02f)
        }
        return EmotionState(
            valence = (decayed.valence + deltas.valence).bounded(),
            arousal = (decayed.arousal + deltas.arousal).bounded(),
            curiosity = (decayed.curiosity + deltas.curiosity).bounded(),
            energy = (decayed.energy + deltas.energy).bounded(),
            engagement = (decayed.engagement + deltas.engagement).bounded(),
            updatedAtMs = nowMs.coerceAtLeast(0L)
        )
    }

    fun decay(state: EmotionState, nowMs: Long): EmotionState {
        val elapsed = (nowMs - state.updatedAtMs).coerceAtLeast(0L)
        if (elapsed == 0L) return state.bounded()
        val retained = 0.5.pow(elapsed.toDouble() / halfLifeMs.toDouble()).toFloat()
        return EmotionState(
            valence = toward(state.valence, EmotionState.BASELINE_VALENCE, retained),
            arousal = toward(state.arousal, EmotionState.BASELINE_AROUSAL, retained),
            curiosity = toward(state.curiosity, EmotionState.BASELINE_CURIOSITY, retained),
            energy = toward(state.energy, EmotionState.BASELINE_ENERGY, retained),
            engagement = toward(state.engagement, EmotionState.BASELINE_ENGAGEMENT, retained),
            updatedAtMs = nowMs.coerceAtLeast(0L)
        )
    }

    private fun toward(value: Float, baseline: Float, retained: Float): Float =
        (baseline + (value.bounded() - baseline) * retained).bounded()

    private fun EmotionState.bounded(): EmotionState = copy(
        valence = valence.bounded(),
        arousal = arousal.bounded(),
        curiosity = curiosity.bounded(),
        energy = energy.bounded(),
        engagement = engagement.bounded()
    )

    private fun Float.bounded(): Float = coerceIn(0f, 1f)

    private data class Deltas(
        val valence: Float = 0f,
        val arousal: Float = 0f,
        val curiosity: Float = 0f,
        val energy: Float = 0f,
        val engagement: Float = 0f
    )

    private companion object {
        const val DEFAULT_HALF_LIFE_MS = 18_000L
    }
}
