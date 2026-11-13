package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSessionSettingsTest {
    @Test
    fun defaultSessionTimeoutIsTwentySecondsAndCanBeDisabled() {
        assertEquals(20_000L, ConversationSessionPolicy.timeoutMillis(ConversationSessionPolicy.DEFAULT_TIMEOUT_SECONDS))
        assertEquals(0L, ConversationSessionPolicy.timeoutMillis(0))
        assertEquals(10_000L, ConversationSessionPolicy.timeoutMillis(10))
        assertEquals(60_000L, ConversationSessionPolicy.timeoutMillis(60))
    }

    @Test
    fun unsupportedTimeoutFallsBackToTheSafeDefault() {
        assertEquals(20_000L, ConversationSessionPolicy.timeoutMillis(7))
        assertEquals("Kapalı", ConversationSessionPolicy.label(0))
        assertEquals("20 saniye", ConversationSessionPolicy.label(20))
    }

    @Test
    fun followUpRetryPreservesPendingTimeoutAndRearmsOnlyWhenMissing() {
        assertEquals(false, ConversationSessionPolicy.shouldArmTimeoutAfterRetry(timeoutAlreadyPending = true))
        assertEquals(true, ConversationSessionPolicy.shouldArmTimeoutAfterRetry(timeoutAlreadyPending = false))
    }
}
