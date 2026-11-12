package com.companion.assistant

/** A durable, ordered mirror operation stored beside the local reminder database. */
internal data class ReminderSyncOperation(
    val sequence: Long,
    val operation: String,
    val reminderId: String,
    val payload: String?
)

internal interface ReminderSyncQueue {
    fun pendingSyncOperations(limit: Int): List<ReminderSyncOperation>
    fun acknowledgeSyncOperation(sequence: Long): Boolean
}

internal interface ReminderSyncApi {
    fun syncSnapshot(payload: String)
    fun delete(id: String)
}

/** Replays one operation at a time and keeps later writes behind the first failed request. */
internal class ReminderSyncDispatcher(
    private val queue: ReminderSyncQueue,
    private val api: ReminderSyncApi
) {
    fun flush(batchSize: Int = MAX_BATCH_SIZE): Int = synchronized(PROCESS_LOCK) {
        var synced = 0
        while (synced < batchSize) {
            val operation = queue.pendingSyncOperations(1).firstOrNull() ?: break
            when (operation.operation) {
                OP_UPSERT -> api.syncSnapshot(operation.payload ?: error("Upsert is missing its snapshot"))
                OP_DELETE -> api.delete(operation.reminderId)
                else -> error("Unknown reminder sync operation")
            }
            check(queue.acknowledgeSyncOperation(operation.sequence)) {
                "Reminder sync operation was not acknowledged"
            }
            synced++
        }
        synced
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
        val PROCESS_LOCK = Any()
        const val OP_UPSERT = "upsert"
        const val OP_DELETE = "delete"
    }
}

internal object ProactiveSpeechGate {
    fun blockedReason(
        settings: ReminderSettings,
        nowMillis: Long,
        zoneId: String,
        robotState: RobotActivityState,
        nextAllowedAt: Long
    ): String? = when {
        !settings.autonomyEnabled -> "otonomi kapalı"
        !settings.proactiveSpeechEnabled -> "sesli hatırlatma izni kapalı"
        ReminderTimePolicy.isQuiet(nowMillis, settings, zoneId) -> "sessiz saatler etkin"
        robotState != RobotActivityState.IDLE -> "robot meşgul"
        nextAllowedAt > nowMillis -> "konuşma bekleme süresi dolmadı"
        else -> null
    }
}

/** Small seam shared by production reminder speech and its deterministic unit tests. */
internal class ProactiveSpeechFlow(
    private val speechRequest: (String, (Boolean) -> Unit) -> Unit
) {
    fun request(message: String, allowed: Boolean, onFinished: (Boolean) -> Unit): Boolean {
        if (!allowed || message.isBlank()) {
            onFinished(false)
            return false
        }
        speechRequest(message, onFinished)
        return true
    }
}
