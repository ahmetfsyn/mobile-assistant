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

    @Test
    fun conversationRequestCarriesTheCurrentMemoryConsentAndBoundedHistory() {
        val history = listOf(
            ConversationMessage("system", "discard this"),
            ConversationMessage("user", "Kahvemi şekersiz içerim."),
            ConversationMessage("assistant", "Tamam.")
        )

        val enabled = encodeConversationBody("Bir kahve içesim var.", VoiceLanguage.TURKISH, history, memoryEnabled = true)
        val disabled = encodeConversationBody("Bir kahve içesim var.", VoiceLanguage.TURKISH, history, memoryEnabled = false)

        assertEquals(true, enabled.memoryEnabled)
        assertEquals(false, disabled.memoryEnabled)
        assertEquals(2, enabled.history.size)
        assertEquals("tr-TR", enabled.languageTag)
        assertEquals("Bir kahve içesim var.", enabled.message)
    }
}
