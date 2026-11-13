package com.companion.assistant

import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide guard for the app's microphone consumers. A lease releases only its own claim. */
internal object MicrophoneOwnership {
    enum class Owner { WAKE_WORD, PUSH_TO_TALK, BARGE_IN }

    class Lease internal constructor(
        val owner: Owner,
        private val token: Any,
        private val releaseToken: (Owner, Any) -> Unit
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) releaseToken(owner, token)
        }
    }

    private var activeOwner: Owner? = null
    private var activeToken: Any? = null

    @Synchronized
    fun acquire(owner: Owner): Lease? {
        if (activeOwner != null) return null
        val token = Any()
        activeOwner = owner
        activeToken = token
        return Lease(owner, token, ::release)
    }

    @Synchronized
    fun currentOwner(): Owner? = activeOwner

    @Synchronized
    private fun release(owner: Owner, token: Any) {
        if (activeOwner == owner && activeToken === token) {
            activeOwner = null
            activeToken = null
        }
    }
}
