package com.companion.assistant

/** One-slot queue used while Android TextToSpeech is initializing. */
internal class PendingSpeechQueue<T> {
    private var pending: T? = null

    @Synchronized fun enqueue(item: T) { pending = item }
    @Synchronized fun take(): T? = pending.also { pending = null }
    @Synchronized fun clear(): T? = take()
    @Synchronized fun fail(onFailure: (T) -> Unit) { take()?.let(onFailure) }
}

/** Drops callbacks after cancellation so a stopped utterance cannot complete an old robot turn. */
internal class SpeechUtteranceCallbacks {
    private data class Active(val id: String, val callback: SpeechOutput.Callback)
    private var active: Active? = null

    val currentId: String? get() = active?.id

    fun begin(id: String, callback: SpeechOutput.Callback) { active = Active(id, callback) }

    fun callbackFor(id: String): SpeechOutput.Callback? = active?.takeIf { it.id == id }?.callback

    fun finish(id: String): SpeechOutput.Callback? {
        val current = active?.takeIf { it.id == id } ?: return null
        active = null
        return current.callback
    }

    fun cancel(): SpeechOutput.Callback? = active?.callback.also { active = null }
}
