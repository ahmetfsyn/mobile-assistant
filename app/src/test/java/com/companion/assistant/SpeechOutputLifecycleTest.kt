package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOutputLifecycleTest {
    @Test
    fun queuedSpeechReceivesInitializationFailureAndIsNotRetained() {
        val queue = PendingSpeechQueue<SpeechOutput.Callback>()
        val callback = RecordingCallback()
        queue.enqueue(callback)

        queue.fail { it.onError("KONUŞMA MOTORU BAŞLATILAMADI") }

        assertEquals("KONUŞMA MOTORU BAŞLATILAMADI", callback.error)
        assertNull(queue.take())
    }

    @Test
    fun cancellingUtteranceDropsLateCompletionAndReleasesCallback() {
        val callbacks = SpeechUtteranceCallbacks()
        val callback = RecordingCallback()
        callbacks.begin("utterance-1", callback)
        assertSame(callback, callbacks.callbackFor("utterance-1"))

        assertSame(callback, callbacks.cancel())

        assertNull(callbacks.callbackFor("utterance-1"))
        assertNull(callbacks.finish("utterance-1"))
        assertNull(callbacks.currentId)
        assertTrue(callback.completed == 0)
    }

    private class RecordingCallback : SpeechOutput.Callback {
        var completed = 0
        var error: String? = null
        override fun onStarted() = Unit
        override fun onCompleted() { completed++ }
        override fun onError(message: String) { error = message }
    }
}
