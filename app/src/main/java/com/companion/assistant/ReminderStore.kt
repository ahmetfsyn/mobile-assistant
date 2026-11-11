package com.companion.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** Device-side schedule cache. Alarm delivery remains available without the Mac or Wi-Fi. */
internal class ReminderStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "companion_reminders.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE reminders (
              id TEXT PRIMARY KEY,
              message TEXT NOT NULL,
              scheduled_at INTEGER NOT NULL,
              time_zone TEXT NOT NULL,
              recurrence TEXT NOT NULL,
              status TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              last_delivered_at INTEGER,
              next_at INTEGER NOT NULL,
              pending_at INTEGER,
              attempts INTEGER NOT NULL DEFAULT 0,
              delivery_state TEXT NOT NULL DEFAULT 'idle'
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX reminders_schedule ON reminders(status, next_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun create(message: String, atMillis: Long, recurrence: String, zoneId: String = TimeZone.getDefault().id): ReminderRecord {
        require(message.isNotBlank() && message.length <= MAX_MESSAGE_LENGTH)
        require(atMillis > System.currentTimeMillis())
        require(recurrence in RECURRENCES)
        val now = System.currentTimeMillis()
        val record = ReminderRecord(
            id = UUID.randomUUID().toString(), message = message.trim(), scheduledAtMillis = atMillis,
            timeZoneId = zoneId, recurrence = recurrence, status = STATUS_SCHEDULED,
            createdAtMillis = now, updatedAtMillis = now, lastDeliveredAtMillis = null,
            nextAtMillis = atMillis, pendingAtMillis = null, attempts = 0, deliveryState = STATE_IDLE
        )
        writableDatabase.insertOrThrow(TABLE, null, record.toValues())
        return record
    }

    @Synchronized
    fun list(includeCompleted: Boolean = true): List<ReminderRecord> {
        val where = if (includeCompleted) "status != ?" else "status IN (?, ?)"
        val args = if (includeCompleted) arrayOf(STATUS_DELETED) else arrayOf(STATUS_SCHEDULED, STATUS_PAUSED)
        val order = "CASE WHEN status IN ('scheduled', 'paused', 'needs_attention') THEN 0 ELSE 1 END ASC, " +
            "CASE WHEN status IN ('scheduled', 'paused', 'needs_attention') THEN next_at END ASC, " +
            "CASE WHEN status NOT IN ('scheduled', 'paused', 'needs_attention') THEN updated_at END DESC, updated_at DESC"
        readableDatabase.query(TABLE, null, where, args, null, null, order, "100").use { cursor ->
            val result = ArrayList<ReminderRecord>(cursor.count)
            while (cursor.moveToNext()) result += cursor.toReminder()
            return result
        }
    }

    @Synchronized
    fun get(id: String): ReminderRecord? = readableDatabase.query(TABLE, null, "id = ? AND status != ?", arrayOf(id, STATUS_DELETED), null, null, null, "1")
        .use { cursor -> if (cursor.moveToFirst()) cursor.toReminder() else null }

    @Synchronized
    fun update(id: String, message: String, atMillis: Long, recurrence: String): ReminderRecord? {
        val current = get(id) ?: return null
        require(message.isNotBlank() && message.length <= MAX_MESSAGE_LENGTH && recurrence in RECURRENCES)
        val updated = current.copy(
            message = message.trim(), scheduledAtMillis = atMillis, recurrence = recurrence,
            nextAtMillis = atMillis, pendingAtMillis = null, attempts = 0, deliveryState = STATE_IDLE,
            status = STATUS_SCHEDULED, updatedAtMillis = System.currentTimeMillis()
        )
        writableDatabase.update(TABLE, updated.toValues(), "id = ?", arrayOf(id))
        return updated
    }

    @Synchronized
    fun pause(id: String): ReminderRecord? = changeStatus(id, STATUS_PAUSED, clearPending = true)

    @Synchronized
    fun resume(id: String): ReminderRecord? {
        val current = get(id) ?: return null
        val next = current.pendingAtMillis ?: current.nextAtMillis
        val updated = current.copy(status = STATUS_SCHEDULED, nextAtMillis = next, pendingAtMillis = null,
            attempts = 0, deliveryState = STATE_IDLE, updatedAtMillis = System.currentTimeMillis())
        writableDatabase.update(TABLE, updated.toValues(), "id = ?", arrayOf(id))
        return updated
    }

    @Synchronized
    fun snooze(id: String, delayMillis: Long): ReminderRecord? {
        val current = get(id) ?: return null
        if (current.status != STATUS_SCHEDULED || delayMillis !in 60_000L..(7L * 24 * 60 * 60 * 1000)) return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            nextAtMillis = now + delayMillis,
            pendingAtMillis = current.pendingAtMillis ?: current.nextAtMillis,
            attempts = 0,
            deliveryState = STATE_DEFERRED,
            updatedAtMillis = now
        )
        writableDatabase.update(TABLE, updated.toValues(), "id = ?", arrayOf(id))
        return updated
    }

    @Synchronized
    fun complete(id: String): ReminderRecord? = changeStatus(id, STATUS_COMPLETED, clearPending = true)

    @Synchronized
    fun delete(id: String): Boolean = changeStatus(id, STATUS_DELETED, clearPending = true) != null

    @Synchronized
    fun pauseAll(): Int {
        val values = ContentValues().apply {
            put("status", STATUS_PAUSED)
            putNull("pending_at")
            put("delivery_state", STATE_IDLE)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update(TABLE, values, "status = ?", arrayOf(STATUS_SCHEDULED))
    }

    @Synchronized
    fun claimDue(id: String, scheduledAt: Long, now: Long): ReminderRecord? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = get(id) ?: return null
            if (current.status != STATUS_SCHEDULED || current.nextAtMillis > now) return null
            if (current.pendingAtMillis == null && current.nextAtMillis != scheduledAt) return null
            if (current.pendingAtMillis != null && current.pendingAtMillis != scheduledAt) return null
            if (current.deliveryState == STATE_PROCESSING && now - current.updatedAtMillis < PROCESSING_TIMEOUT_MS) return null
            if (current.deliveryState == STATE_DONE || current.deliveryState == STATE_FAILED) return null
            if (current.attempts >= MAX_RETRY_ATTEMPTS) {
                val failed = current.copy(status = STATUS_NEEDS_ATTENTION, deliveryState = STATE_FAILED, updatedAtMillis = now)
                db.update(TABLE, failed.toValues(), "id = ?", arrayOf(id))
                db.setTransactionSuccessful()
                return failed
            }
            val claimed = current.copy(
                pendingAtMillis = current.pendingAtMillis ?: scheduledAt,
                attempts = current.attempts + 1,
                deliveryState = STATE_PROCESSING,
                updatedAtMillis = now
            )
            db.update(TABLE, claimed.toValues(), "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
            return claimed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun defer(id: String, scheduledAt: Long, retryAt: Long, failedAttempt: Boolean): ReminderRecord? {
        val current = get(id) ?: return null
        if (current.status != STATUS_SCHEDULED) return null
        if (current.pendingAtMillis != null && current.pendingAtMillis != scheduledAt) return null
        val nextAttempts = if (failedAttempt) current.attempts else (current.attempts - 1).coerceAtLeast(0)
        val exhausted = failedAttempt && nextAttempts >= MAX_RETRY_ATTEMPTS
        val updated = current.copy(
            pendingAtMillis = current.pendingAtMillis ?: scheduledAt,
            nextAtMillis = if (exhausted) current.nextAtMillis else retryAt,
            status = if (exhausted) STATUS_NEEDS_ATTENTION else current.status,
            attempts = nextAttempts,
            deliveryState = if (exhausted) STATE_FAILED else STATE_DEFERRED,
            updatedAtMillis = System.currentTimeMillis()
        )
        writableDatabase.update(TABLE, updated.toValues(), "id = ?", arrayOf(id))
        return updated
    }

    @Synchronized
    fun deferQuiet(id: String, scheduledAt: Long, retryAt: Long): ReminderRecord? {
        val current = get(id) ?: return null
        if (current.status != STATUS_SCHEDULED) return null
        if (current.pendingAtMillis != null && current.pendingAtMillis != scheduledAt) return null
        val updated = current.copy(pendingAtMillis = current.pendingAtMillis ?: scheduledAt, nextAtMillis = retryAt,
            deliveryState = STATE_DEFERRED, updatedAtMillis = System.currentTimeMillis())
        writableDatabase.update(TABLE, updated.toValues(), "id = ?", arrayOf(id))
        return updated
    }

    @Synchronized
    fun resetInFlight() {
        val values = ContentValues().apply { put("delivery_state", STATE_DEFERRED) }
        writableDatabase.update(TABLE, values, "delivery_state = ?", arrayOf(STATE_PROCESSING))
    }

    @Synchronized
    fun markDelivered(id: String, scheduledAt: Long, deliveredAt: Long): ReminderRecord? {
        val current = get(id) ?: return null
        if (current.status !in setOf(STATUS_SCHEDULED, STATUS_NEEDS_ATTENTION) || current.pendingAtMillis != scheduledAt) return null
        val next = ReminderTimePolicy.nextOccurrence(current.scheduledAtMillis, current.recurrence, current.timeZoneId, deliveredAt)
        val updated = current.copy(
            status = if (next == null) STATUS_DELIVERED else STATUS_SCHEDULED,
            nextAtMillis = next ?: current.nextAtMillis,
            pendingAtMillis = null,
            lastDeliveredAtMillis = deliveredAt,
            attempts = 0,
            deliveryState = if (next == null) STATE_DONE else STATE_IDLE,
            updatedAtMillis = deliveredAt
        )
        writableDatabase.update(TABLE, updated.toValues(), "id = ?", arrayOf(id))
        return updated
    }

    @Synchronized
    fun recoverDue(now: Long = System.currentTimeMillis()): List<ReminderRecord> {
        resetInFlight()
        return list(includeCompleted = false).filter { it.status == STATUS_SCHEDULED && it.nextAtMillis <= now }
    }

    private fun changeStatus(id: String, status: String, clearPending: Boolean): ReminderRecord? {
        val current = get(id) ?: return null
        val updated = current.copy(status = status, pendingAtMillis = if (clearPending) null else current.pendingAtMillis,
            deliveryState = STATE_IDLE, updatedAtMillis = System.currentTimeMillis())
        writableDatabase.update(TABLE, updated.toValues(), "id = ?", arrayOf(id))
        return updated
    }

    private fun ReminderRecord.toValues() = ContentValues().apply {
        put("id", id); put("message", message); put("scheduled_at", scheduledAtMillis); put("time_zone", timeZoneId)
        put("recurrence", recurrence); put("status", status); put("created_at", createdAtMillis); put("updated_at", updatedAtMillis)
        if (lastDeliveredAtMillis == null) putNull("last_delivered_at") else put("last_delivered_at", lastDeliveredAtMillis)
        put("next_at", nextAtMillis)
        if (pendingAtMillis == null) putNull("pending_at") else put("pending_at", pendingAtMillis)
        put("attempts", attempts); put("delivery_state", deliveryState)
    }

    private fun android.database.Cursor.toReminder() = ReminderRecord(
        id = getString(getColumnIndexOrThrow("id")), message = getString(getColumnIndexOrThrow("message")),
        scheduledAtMillis = getLong(getColumnIndexOrThrow("scheduled_at")), timeZoneId = getString(getColumnIndexOrThrow("time_zone")),
        recurrence = getString(getColumnIndexOrThrow("recurrence")), status = getString(getColumnIndexOrThrow("status")),
        createdAtMillis = getLong(getColumnIndexOrThrow("created_at")), updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")),
        lastDeliveredAtMillis = if (isNull(getColumnIndexOrThrow("last_delivered_at"))) null else getLong(getColumnIndexOrThrow("last_delivered_at")),
        nextAtMillis = getLong(getColumnIndexOrThrow("next_at")),
        pendingAtMillis = if (isNull(getColumnIndexOrThrow("pending_at"))) null else getLong(getColumnIndexOrThrow("pending_at")),
        attempts = getInt(getColumnIndexOrThrow("attempts")), deliveryState = getString(getColumnIndexOrThrow("delivery_state"))
    )

    private companion object {
        const val TABLE = "reminders"
        const val MAX_MESSAGE_LENGTH = 240
        const val MAX_RETRY_ATTEMPTS = 3
        const val PROCESSING_TIMEOUT_MS = 120_000L
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_DELETED = "deleted"
        const val STATUS_NEEDS_ATTENTION = "needs_attention"
        const val STATE_IDLE = "idle"
        const val STATE_PROCESSING = "processing"
        const val STATE_DEFERRED = "deferred"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
        val RECURRENCES = setOf("none", "daily", "weekdays", "weekly:MO", "weekly:TU", "weekly:WE", "weekly:TH", "weekly:FR", "weekly:SA", "weekly:SU")
    }
}

internal class ReminderSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("companion_autonomy", Context.MODE_PRIVATE)

    @Synchronized
    fun get(): ReminderSettings = ReminderSettings(
        autonomyEnabled = preferences.getBoolean(KEY_AUTONOMY, false),
        proactiveSpeechEnabled = preferences.getBoolean(KEY_SPEECH, false),
        quietHoursEnabled = preferences.getBoolean(KEY_QUIET, true),
        quietHoursStart = preferences.getString(KEY_START, "23:00") ?: "23:00",
        quietHoursEnd = preferences.getString(KEY_END, "08:00") ?: "08:00"
    )

    @Synchronized
    fun save(settings: ReminderSettings) {
        preferences.edit()
            .putBoolean(KEY_AUTONOMY, settings.autonomyEnabled)
            .putBoolean(KEY_SPEECH, settings.proactiveSpeechEnabled)
            .putBoolean(KEY_QUIET, settings.quietHoursEnabled)
            .putString(KEY_START, settings.quietHoursStart)
            .putString(KEY_END, settings.quietHoursEnd)
            .apply()
    }

    private companion object {
        const val KEY_AUTONOMY = "autonomy_enabled"
        const val KEY_SPEECH = "proactive_speech_enabled"
        const val KEY_QUIET = "quiet_hours_enabled"
        const val KEY_START = "quiet_hours_start"
        const val KEY_END = "quiet_hours_end"
    }
}
