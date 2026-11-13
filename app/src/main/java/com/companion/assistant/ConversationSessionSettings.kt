package com.companion.assistant

import android.content.Context

internal object ConversationSessionPolicy {
    const val DEFAULT_TIMEOUT_SECONDS = 5
    val timeoutOptionsSeconds = listOf(0, 5, 10, 20, 30, 60)

    fun timeoutMillis(seconds: Int): Long = when (seconds) {
        in timeoutOptionsSeconds -> seconds * 1_000L
        else -> DEFAULT_TIMEOUT_SECONDS * 1_000L
    }

    fun shouldArmTimeoutAfterRetry(timeoutAlreadyPending: Boolean): Boolean = !timeoutAlreadyPending

    fun shouldRetryAfterFollowUpFailure(speechDetected: Boolean, timeoutAlreadyPending: Boolean): Boolean =
        speechDetected || !timeoutAlreadyPending

    fun label(seconds: Int): String = if (seconds == 0) "Kapalı" else "$seconds saniye"
}

/** User-controlled duration before the open-mic conversation session closes. */
internal class ConversationSessionSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun timeoutSeconds(): Int = preferences.getInt(KEY_TIMEOUT_SECONDS, ConversationSessionPolicy.DEFAULT_TIMEOUT_SECONDS)
        .takeIf { it in ConversationSessionPolicy.timeoutOptionsSeconds }
        ?: ConversationSessionPolicy.DEFAULT_TIMEOUT_SECONDS

    fun timeoutMillis(): Long = ConversationSessionPolicy.timeoutMillis(timeoutSeconds())

    fun setTimeoutSeconds(seconds: Int) {
        require(seconds in ConversationSessionPolicy.timeoutOptionsSeconds)
        preferences.edit().putInt(KEY_TIMEOUT_SECONDS, seconds).apply()
    }

    private companion object {
        const val PREFERENCES = "conversation_session_settings"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
    }
}
