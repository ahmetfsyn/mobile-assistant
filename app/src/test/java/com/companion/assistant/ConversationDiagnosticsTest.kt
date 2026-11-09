package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDiagnosticsTest {
    @Test
    fun missingBackendDoesNotSilentlySelectTheStaticConversationMock() {
        assertEquals(ConversationEngineMode.UNCONFIGURED, ConversationEngineFactory.modeFor(""))
        assertEquals(ConversationEngineMode.UNCONFIGURED, ConversationEngineFactory.modeFor("  "))
        assertEquals(ConversationEngineMode.HTTP, ConversationEngineFactory.modeFor("https://assistant.example.test"))
        assertEquals(ConversationEngineMode.MOCK, ConversationEngineFactory.modeFor("", useMock = true))
        assertTrue(ConversationEngineFactory.create("") is UnconfiguredConversationEngine)
        assertTrue(ConversationEngineFactory.create("", useMock = true) is MockConversationEngine)
    }

    @Test
    fun fingerprintsAreInteractionScopedAndPreviewMasksWords() {
        val message = "Türkiye'nin başkenti neresidir?"
        val first = VoiceDiagnostics.fingerprint("b0000000-0000-4000-8000-000000000000", message)
        val second = VoiceDiagnostics.fingerprint("b0000001-0000-4000-8000-000000000001", message)
        val preview = VoiceDiagnostics.redactedPreview(message)

        assertEquals(16, first.length)
        assertNotEquals(first, second)
        assertFalse(preview.contains("Türkiye"))
        assertFalse(preview.contains("başkenti"))
        assertFalse(preview.contains("neresidir"))
        assertTrue(preview.startsWith("T"))
    }

    @Test
    fun emptyPreviewDoesNotExposeOrInventTranscriptContent() {
        assertEquals("[empty]", VoiceDiagnostics.redactedPreview("   "))
    }
}
