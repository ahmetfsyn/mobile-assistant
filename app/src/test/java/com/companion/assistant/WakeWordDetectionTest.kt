package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WakeWordDetectionTest {
    @Test
    fun matcherAcceptsExactTurkishPhraseAndUnicodeNormalizationOnly() {
        assertTrue(WakeWordMatcher.isExactAyse("Ayşe"))
        assertTrue(WakeWordMatcher.isExactAyse("AYŞE."))
        assertTrue(WakeWordMatcher.isExactAyse("ayşe!"))
        assertTrue(WakeWordMatcher.isExactAyse("Ayşe")) // decomposed ş becomes NFC
        assertFalse(WakeWordMatcher.isExactAyse("ayse"))
        assertFalse(WakeWordMatcher.isExactAyse("Aisha"))
        assertFalse(WakeWordMatcher.isExactAyse("Hey Robot"))
        assertFalse(WakeWordMatcher.isExactAyse("Ayşe merhaba"))
        assertFalse(WakeWordMatcher.isExactAyse(""))
        assertFalse(WakeWordMatcher.isExactAyse(null))
    }

    @Test
    fun finalResultGateRejectsUnrelatedPartialAndDuplicateResults() {
        val gate = WakeWordDetectionGate()
        assertFalse(gate.acceptFinalResult("Ayşe merhaba"))
        assertFalse(gate.acceptFinalResult("[partial: Ayşe]"))
        assertTrue(gate.acceptFinalResult("Ayşe"))
        assertFalse(gate.acceptFinalResult("Ayşe"))
    }

    @Test
    fun monitorConsumesFinalResultsLocallyAndReleasesResourcesOnDetection() {
        val source = FakeAudioSource(3)
        val engine = FakeWakeWordEngine(listOf(null, "Ayşe merhaba", "AYŞE."))
        val monitor = WakeWordMonitor(source, engine)
        try {
            assertTrue(monitor.runUntilDetected())
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
    fun failedAudioStartAndReadReleaseMicrophoneAndEngine() {
        val failedStart = FakeAudioSource(0, startWorks = false)
        val engine = FakeWakeWordEngine(emptyList())
        val monitor = WakeWordMonitor(failedStart, engine)
        try {
            val error = runCatching { monitor.runUntilDetected() }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
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

    private class FakeWakeWordEngine(results: List<String?>) : WakeWordEngine {
        private val pending = results.toMutableList()
        var closes = 0
        override fun acceptAudio(pcm16le: ByteArray, length: Int): String? = if (pending.isEmpty()) null else pending.removeAt(0)
        override fun close() { closes++ }
    }
}
