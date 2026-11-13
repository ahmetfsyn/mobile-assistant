package com.companion.assistant

import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformSpeechInputTest {
    @Test
    fun successfulRecognitionReturnsActualTranscriptAndReleasesEngine() {
        val fixture = Fixture()
        val callback = TestCallback()

        fixture.input.startListening(1L, VoiceLanguage.TURKISH, callback)
        val engine = fixture.factory.engines.single()
        assertEquals(VoiceLanguage.TURKISH, engine.language)
        assertTrue(engine.preferOffline == false)
        engine.results("Bugün hava çok güzel.")

        assertEquals(listOf("Bugün hava çok güzel."), callback.transcripts)
        assertTrue(callback.errors.isEmpty())
        assertEquals(1, engine.destroyCount)
    }

    @Test
    fun emptyRecognitionResultIsReturnedAsEmptyWithoutStartingAnotherRequest() {
        val fixture = Fixture()
        val callback = TestCallback()
        fixture.input.startListening(2L, VoiceLanguage.TURKISH, callback)
        fixture.factory.engines.single().results("  ")

        assertEquals(listOf(""), callback.transcripts)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun beginningOfSpeechIsForwardedSoTheCommandTimeoutCanBeCancelled() {
        val fixture = Fixture()
        val callback = TestCallback()

        fixture.input.startListening(22L, VoiceLanguage.TURKISH, callback)
        fixture.factory.engines.single().beginningOfSpeech()

        assertEquals(listOf(22L), callback.speechStartedSessions)
    }

    @Test
    fun speechTimeoutAndNoMatchRemainDistinctRecognitionFailures() {
        assertError(
            AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            "Ses algılanamadı."
        )
        assertError(AndroidSpeechRecognizer.ERROR_NO_MATCH, "Konuşma anlaşılamadı.")
    }

    @Test
    fun busyClientAndServiceFailuresHaveSpecificMessages() {
        assertError(
            AndroidSpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            "Ses tanıma servisi meşgul. Birkaç saniye sonra yeniden dene."
        )
        assertError(
            AndroidSpeechRecognizer.ERROR_SERVER,
            "Ses tanıma servisi yanıt vermedi."
        )
        assertError(
            AndroidSpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            "Konuşma tanıma servisi bağlantısı kesildi."
        )
        assertError(
            AndroidSpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            "Ses tanıma isteği sınırına ulaşıldı. Biraz sonra yeniden dene."
        )
    }

    @Test
    fun microphonePermissionDenialIsReportedClearly() {
        assertError(
            AndroidSpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            "Mikrofon izni gerekli."
        )
    }

    @Test
    fun duplicateStartForSameSessionDoesNotCreateAnotherRecognizer() {
        val fixture = Fixture()
        val callback = TestCallback()

        fixture.input.startListening(3L, VoiceLanguage.TURKISH, callback)
        fixture.input.startListening(3L, VoiceLanguage.TURKISH, callback)

        assertEquals(1, fixture.factory.engines.size)
        assertEquals(1, fixture.factory.engines.single().startCount)
    }

    @Test
    fun rapidCancelAndRestartRejectsCallbacksFromTheOldSession() {
        val fixture = Fixture()
        val oldCallback = TestCallback()
        val newCallback = TestCallback()

        fixture.input.startListening(4L, VoiceLanguage.TURKISH, oldCallback)
        val oldEngine = fixture.factory.engines[0]
        fixture.input.cancel()
        fixture.input.startListening(5L, VoiceLanguage.TURKISH, newCallback)
        val newEngine = fixture.factory.engines[1]

        oldEngine.results("Eski oturum")
        newEngine.results("Yeni oturum")

        assertTrue(oldCallback.transcripts.isEmpty())
        assertEquals(listOf("Yeni oturum"), newCallback.transcripts)
        assertEquals(1, oldEngine.cancelCount)
        assertEquals(1, oldEngine.destroyCount)
    }

    @Test
    fun replacingActiveSessionCleansOldEngineAndIgnoresItsLateError() {
        val fixture = Fixture()
        val firstCallback = TestCallback()
        val secondCallback = TestCallback()
        fixture.input.startListening(6L, VoiceLanguage.TURKISH, firstCallback)
        val firstEngine = fixture.factory.engines[0]

        fixture.input.startListening(7L, VoiceLanguage.TURKISH, secondCallback)
        firstEngine.error(AndroidSpeechRecognizer.ERROR_CLIENT)
        fixture.factory.engines[1].results("Yeni")

        assertTrue(firstCallback.errors.isEmpty())
        assertEquals(listOf("Yeni"), secondCallback.transcripts)
        assertEquals(1, firstEngine.destroyCount)
    }

    @Test
    fun offlineClientFailureRetriesOnceWithNetworkAllowedThenReturnsTranscript() {
        val fixture = Fixture(preferOffline = true)
        val callback = TestCallback()
        fixture.input.startListening(8L, VoiceLanguage.TURKISH, callback)
        val offlineEngine = fixture.factory.engines[0]

        offlineEngine.error(AndroidSpeechRecognizer.ERROR_CLIENT)

        assertEquals(2, fixture.factory.engines.size)
        val retryEngine = fixture.factory.engines[1]
        assertTrue(retryEngine.preferOffline == false)
        assertEquals(1, offlineEngine.destroyCount)
        assertTrue(callback.errors.isEmpty())

        retryEngine.results("Ağ ile tanınan cümle")
        assertEquals(listOf("Ağ ile tanınan cümle"), callback.transcripts)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun offlineFailureAfterSpeechBeginsDoesNotRetryAutomatically() {
        val fixture = Fixture(preferOffline = true)
        val callback = TestCallback()
        fixture.input.startListening(9L, VoiceLanguage.TURKISH, callback)
        val engine = fixture.factory.engines.single()
        engine.beginningOfSpeech()

        engine.error(AndroidSpeechRecognizer.ERROR_CLIENT)

        assertEquals(1, fixture.factory.engines.size)
        assertEquals(listOf("Ses tanıma oturumu kesildi."), callback.errors)
    }

    @Test
    fun unavailableRecognitionServiceReturnsClearErrorAndClearsRequest() {
        val fixture = Fixture(available = false)
        val callback = TestCallback()

        fixture.input.startListening(10L, VoiceLanguage.TURKISH, callback)
        fixture.input.startListening(11L, VoiceLanguage.TURKISH, TestCallback())

        assertEquals(0, fixture.factory.engines.size)
        assertEquals(listOf("BU CİHAZDA KONUŞMA TANIMA SERVİSİ BULUNAMADI"), callback.errors)
    }

    @Test
    fun clientFailureInNormalModeRecreatesOnlyOnceWithoutSwitchingToOfflineOnly() {
        val fixture = Fixture()
        val callback = TestCallback()
        fixture.input.startListening(12L, VoiceLanguage.TURKISH, callback)
        fixture.factory.engines[0].error(AndroidSpeechRecognizer.ERROR_CLIENT)

        val retry = fixture.factory.engines[1]
        assertTrue(retry.preferOffline == false)
        retry.error(AndroidSpeechRecognizer.ERROR_CLIENT)

        assertEquals(2, fixture.factory.engines.size)
        assertEquals(listOf("Ses tanıma oturumu kesildi."), callback.errors)
    }

    @Test
    fun errorDescriptionsRemainSpecificForAndroidRecognitionCodes() {
        assertEquals("audio_input", SpeechRecognitionErrors.forCode(AndroidSpeechRecognizer.ERROR_AUDIO).description)
        assertEquals("network_unavailable", SpeechRecognitionErrors.forCode(AndroidSpeechRecognizer.ERROR_NETWORK).description)
        assertEquals("network_timeout", SpeechRecognitionErrors.forCode(AndroidSpeechRecognizer.ERROR_NETWORK_TIMEOUT).description)
    }

    private fun assertError(code: Int, expected: String) {
        val fixture = Fixture()
        val callback = TestCallback()
        fixture.input.startListening(20L + code, VoiceLanguage.TURKISH, callback)
        val engine = fixture.factory.engines.single()
        if (code == AndroidSpeechRecognizer.ERROR_CLIENT ||
            code == AndroidSpeechRecognizer.ERROR_SERVER_DISCONNECTED
        ) {
            engine.beginningOfSpeech()
        }
        engine.error(code)
        assertEquals(listOf(expected), callback.errors)
        assertTrue(callback.transcripts.isEmpty())
    }

    private class Fixture(
        available: Boolean = true,
        preferOffline: Boolean = false
    ) {
        val factory = FakeEngineFactory(available)
        val input = PlatformSpeechInput(
            factory,
            ImmediateDispatcher(),
            TestDiagnostics(),
            offlinePreference = { preferOffline }
        )
    }

    private class TestCallback : SpeechInput.Callback {
        val transcripts = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val speechStartedSessions = mutableListOf<Long>()

        override fun onRecordingReady(sessionId: Long, recording: AudioRecording) = Unit
        override fun onTranscriptReady(sessionId: Long, transcript: String) {
            transcripts += transcript
        }

        override fun onError(sessionId: Long, message: String) {
            errors += message
        }

        override fun onSpeechStarted(sessionId: Long) {
            speechStartedSessions += sessionId
        }
    }

    private class ImmediateDispatcher : SpeechInputDispatcher {
        override fun dispatch(action: () -> Unit) = action()
    }

    private class TestDiagnostics : SpeechInputDiagnostics {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String) = Unit
        override fun debug(message: String) = Unit
    }

    private class FakeEngineFactory(private val available: Boolean) : SpeechRecognitionEngineFactory {
        val engines = mutableListOf<FakeEngine>()

        override fun isAvailable() = available

        override fun create(): SpeechRecognitionEngine = FakeEngine().also(engines::add)
    }

    private class FakeEngine : SpeechRecognitionEngine {
        private var listener: SpeechRecognitionEngine.Listener? = null
        var language: VoiceLanguage? = null
            private set
        var preferOffline: Boolean? = null
            private set
        var startCount = 0
            private set
        var cancelCount = 0
            private set
        var destroyCount = 0
            private set

        override fun startListening(
            language: VoiceLanguage,
            preferOffline: Boolean,
            listener: SpeechRecognitionEngine.Listener
        ) {
            this.language = language
            this.preferOffline = preferOffline
            this.listener = listener
            startCount += 1
        }

        override fun cancel() {
            cancelCount += 1
        }

        override fun destroy() {
            destroyCount += 1
        }

        fun beginningOfSpeech() = listener?.onBeginningOfSpeech()
        fun results(transcript: String) = listener?.onResults(transcript)
        fun error(code: Int) = listener?.onError(code)
    }
}
