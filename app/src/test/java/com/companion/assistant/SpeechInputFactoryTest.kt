package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechInputFactoryTest {
    @Test
    fun platformRecognizerIsTheDefaultWhenNoEndpointOrMockIsConfigured() {
        assertEquals(SpeechInputMode.PLATFORM, SpeechInputFactory.modeFor("", useMock = false))
        assertEquals(SpeechInputMode.PLATFORM, SpeechInputFactory.modeFor("   ", useMock = false))
    }

    @Test
    fun configuredHttpsEndpointSelectsPcmRecognition() {
        assertEquals(
            SpeechInputMode.CLOUD_PCM,
            SpeechInputFactory.modeFor("https://speech.example.test/recognize", useMock = false)
        )
    }

    @Test
    fun mockRecognizerRequiresExplicitOptIn() {
        assertEquals(SpeechInputMode.MOCK_PCM, SpeechInputFactory.modeFor("", useMock = true))
    }

    @Test
    fun configuredEndpointTakesPrecedenceOverMockMode() {
        assertEquals(
            SpeechInputMode.CLOUD_PCM,
            SpeechInputFactory.modeFor("https://speech.example.test/recognize", useMock = true)
        )
    }
}
