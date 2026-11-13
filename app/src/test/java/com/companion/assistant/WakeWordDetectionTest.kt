package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WakeWordDetectionTest {
    @Test
    fun audioStartRetryPolicyAllowsFourTransientAttempts() {
        assertFalse(WakeWordAudioStartRetryPolicy.shouldRetry(0))
        assertTrue(WakeWordAudioStartRetryPolicy.shouldRetry(1))
        assertTrue(WakeWordAudioStartRetryPolicy.shouldRetry(4))
        assertFalse(WakeWordAudioStartRetryPolicy.shouldRetry(5))
    }

    @Test
    fun fastWakePathOnlySkipsFullCommandDecodeForShortUtterances() {
        assertTrue(WakeWordFastPathPolicy.isShortUtterance(1_408))
        assertTrue(WakeWordFastPathPolicy.isShortUtterance(1_601))
        assertFalse(WakeWordFastPathPolicy.isShortUtterance(2_001))
        assertFalse(WakeWordFastPathPolicy.isShortUtterance(0))
    }

    @Test
    fun wakeConfirmationUsesBoundedAudioTailWithAlignedSamples() {
        val audio = ByteArray(64_000) { (it % 127).toByte() }
        val window = WakeWordAudioWindow.trailing(audio)

        assertEquals(38_400, window.size)
        assertEquals(audio[25_600], window.first())
        assertEquals(audio[63_999], window.last())

        val shorterWindow = WakeWordAudioWindow.trailing(audio, maxWindowMs = 750)
        assertEquals(24_000, shorterWindow.size)
        assertEquals(audio[40_000], shorterWindow.first())
    }

    @Test
    fun wakeConfirmationKeepsShortAudioAndDropsIncompletePcmSample() {
        val audio = byteArrayOf(1, 2, 3, 4, 5)

        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(WakeWordAudioWindow.trailing(audio)))
    }

    @Test
    fun wakeConfidenceAcceptsMeasuredAyseCandidatesAndRejectsLowerScores() {
        assertTrue(WakeWordConfidencePolicy.accepts(0.573))
        assertTrue(WakeWordConfidencePolicy.accepts(0.683))
        assertTrue(WakeWordConfidencePolicy.accepts(0.697))
        assertFalse(WakeWordConfidencePolicy.accepts(0.549))
    }

    @Test
    fun matcherRequiresAyseAsFirstStandaloneWordAndKeepsCommandRemainder() {
        assertTrue(WakeWordMatcher.isExactAyse("Ayşe"))
        assertTrue(WakeWordMatcher.isExactAyse("AYŞE."))
        assertTrue(WakeWordMatcher.isExactAyse("ayşe!"))
        assertTrue(WakeWordMatcher.isExactAyse("Ayşe")) // decomposed ş becomes NFC
        assertEquals("sabah 9'a alarm kur", WakeWordMatcher.match("Ayşe, sabah 9'a alarm kur.")?.commandTranscript)
        assertEquals("saat kaç", WakeWordMatcher.match("AYŞE saat kaç?")?.commandTranscript)
        assertFalse(WakeWordMatcher.isExactAyse("ayse"))
        assertFalse(WakeWordMatcher.isExactAyse("Aisha"))
        assertFalse(WakeWordMatcher.isExactAyse("Hey Robot"))
        assertFalse(WakeWordMatcher.isExactAyse("Ayşe merhaba"))
        assertFalse(WakeWordMatcher.isExactAyse(""))
        assertFalse(WakeWordMatcher.isExactAyse(null))
        listOf("Aylin", "Ayşegül", "Ayşe'nin", "Ayşelik", "Bana Ayşe de", "Ayşe, hava nasıl?")
            .forEach { assertFalse(WakeWordMatcher.isExactAyse(it)) }
        assertEquals("hava nasıl", WakeWordMatcher.match("Ayşe, hava nasıl?")?.commandTranscript)
        assertEquals(null, WakeWordMatcher.match("Aylin hava nasıl?"))
        assertEquals(null, WakeWordMatcher.match("Ayşegül, hava nasıl?"))
        assertTrue(WakeWordMatcher.isKnownConfusableStart("Ayşegül"))
        assertTrue(WakeWordMatcher.isKnownConfusableStart("AYŞE’NİN adı"))
        assertTrue(WakeWordMatcher.isKnownConfusableStart("Aylin merhaba"))
        assertFalse(WakeWordMatcher.isKnownConfusableStart("Ayşe, saat kaç"))
    }

    @Test
    fun finalResultGateRejectsUnrelatedPartialAndDuplicateResults() {
        val gate = WakeWordDetectionGate()
        assertNull(gate.acceptFinalResult("Aylin merhaba"))
        assertNull(gate.acceptFinalResult("[partial: Ayşe]"))
        assertEquals("merhaba", gate.acceptFinalResult("Ayşe, merhaba")?.commandTranscript)
        assertNull(gate.acceptFinalResult("Ayşe"))

    }

    @Test
    fun monitorConsumesFinalResultsLocallyAndReleasesResourcesOnDetection() {
        val source = FakeAudioSource(3)
        val engine = FakeWakeWordEngine(listOf(null, "merhaba Ayşe", "AYŞE."))
        val monitor = WakeWordMonitor(source, engine)
        try {
            assertNotNull(monitor.runUntilDetected())
            assertEquals(3, source.reads)
            assertEquals(MicrophoneOwnership.Owner.WAKE_WORD, source.ownerDuringRead)
            assertEquals(1, source.closes)
            assertEquals(1, engine.closes)
            assertNull(MicrophoneOwnership.currentOwner())
            assertTrue(source.bufferAfterClose!!.all { it == 0.toByte() })
        } finally {
            monitor.close()
        }
    }

    @Test
    fun monitorRejectsSimilarNamesAndHandsOffFullAyseCommand() {
        val source = FakeAudioSource(5)
        val engine = FakeWakeWordEngine(listOf("Aylin", "Ayşegül", "Ayşe'nin", "Bana Ayşe de", "Ayşe, saat kaç?"))
        val monitor = WakeWordMonitor(source, engine)
        try {
            assertEquals("saat kaç", monitor.runUntilDetected()?.commandTranscript)
            assertEquals(5, source.reads)
            assertEquals(1, source.closes)
            assertEquals(1, engine.closes)
            assertNull(MicrophoneOwnership.currentOwner())
            assertTrue(source.bufferAfterClose!!.all { it == 0.toByte() })
        } finally {
            monitor.close()
        }
    }

    @Test
    fun wakeListenerRearmsStaleStatusesAfterRestartOnlyWithOptInPermissionAndSleep() {
        fun canStart(status: WakeWordStatus, enabled: Boolean = true, permission: Boolean = true, wakeAllowed: Boolean = true) =
            WakeWordServiceStartPolicy.shouldStart(enabled, permission, wakeAllowed, status)

        assertTrue(canStart(WakeWordStatus.DETECTED))
        assertTrue(canStart(WakeWordStatus.PAUSED))
        assertTrue(canStart(WakeWordStatus.LISTENING))
        assertTrue(canStart(WakeWordStatus.STARTING))
        assertTrue(canStart(WakeWordStatus.LOADING_MODEL))
        assertFalse(canStart(WakeWordStatus.ERROR))
        assertFalse(canStart(WakeWordStatus.DETECTED, enabled = false))
        assertFalse(canStart(WakeWordStatus.DETECTED, permission = false))
        assertFalse(canStart(WakeWordStatus.DETECTED, wakeAllowed = false))
        assertTrue(WakeWordServiceStartPolicy.canMonitor(RobotActivityState.SESSION_TIMEOUT))
        assertTrue(WakeWordServiceStartPolicy.canMonitor(RobotActivityState.RETURNING_TO_SLEEP))
        assertTrue(WakeWordServiceStartPolicy.canMonitor(RobotActivityState.SLEEPING))
        assertTrue(WakeWordServiceStartPolicy.canMonitor(RobotActivityState.AWAKE_GRACE))
        assertFalse(WakeWordServiceStartPolicy.canMonitor(RobotActivityState.SPEAKING))
    }

    @Test
    fun failedAudioStartAndReadReleaseMicrophoneAndEngine() {
        val failedStart = FakeAudioSource(0, startWorks = false)
        val engine = FakeWakeWordEngine(emptyList())
        val monitor = WakeWordMonitor(failedStart, engine)
        try {
            val error = runCatching { monitor.runUntilDetected() }.exceptionOrNull()
            assertTrue(error is WakeWordAudioStartException)
            assertEquals("AUDIO_RECORD_START_FAILED", (error as WakeWordAudioStartException).reason)
            assertEquals(1, failedStart.closes)
            assertEquals(1, engine.closes)
            assertNull(MicrophoneOwnership.currentOwner())
        } finally {
            monitor.close()
        }

        val brokenSource = FakeAudioSource(1, readError = true)
        val brokenEngine = FakeWakeWordEngine(emptyList())
        val brokenMonitor = WakeWordMonitor(brokenSource, brokenEngine)
        try {
            assertTrue(runCatching { brokenMonitor.runUntilDetected() }.exceptionOrNull() is IOException)
            assertEquals(1, brokenSource.closes)
            assertEquals(1, brokenEngine.closes)
            assertNull(MicrophoneOwnership.currentOwner())
        } finally {
            brokenMonitor.close()
        }
    }

    @Test
    fun onlyOneCaptureOwnerCanHoldMicrophoneAndOldLeaseCannotReleaseNewOne() {
        val wake = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.WAKE_WORD)!!
        assertNull(MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.PUSH_TO_TALK))
        wake.close()

        val pushToTalk = MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.PUSH_TO_TALK)!!
        wake.close()
        assertEquals(MicrophoneOwnership.Owner.PUSH_TO_TALK, MicrophoneOwnership.currentOwner())
        assertNull(MicrophoneOwnership.acquire(MicrophoneOwnership.Owner.WAKE_WORD))
        pushToTalk.close()
        assertNull(MicrophoneOwnership.currentOwner())
    }

    private class FakeAudioSource(
        private val suppliedBuffers: Int,
        private val startWorks: Boolean = true,
        private val readError: Boolean = false
    ) : WakeWordAudioSource {
        var reads = 0
        var closes = 0
        var ownerDuringRead: MicrophoneOwnership.Owner? = null
        var bufferAfterClose: ByteArray? = null
        private var lastBuffer: ByteArray? = null

        override fun start(): Boolean = startWorks

        override fun read(buffer: ByteArray): Int {
            if (readError) throw IOException("mock audio read error")
            if (reads >= suppliedBuffers) return 0
            ownerDuringRead = MicrophoneOwnership.currentOwner()
            buffer.fill(1)
            lastBuffer = buffer
            reads++
            return 16
        }

        override fun stop() = Unit

        override fun close() {
            closes++
            bufferAfterClose = lastBuffer
        }
    }

    private class FakeWakeWordEngine(
        results: List<String?>
    ) : WakeWordEngine {
        private val pending = results.toMutableList()
        var closes = 0
        override fun acceptAudio(pcm16le: ByteArray, length: Int): String? = if (pending.isEmpty()) null else pending.removeAt(0)
        override fun close() { closes++ }
    }
}
