package com.companion.assistant

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone

/** Best-effort authenticated mirror. Android SQLite and AlarmManager remain the delivery authority. */
internal class ReminderApiClient(
    private val endpoint: String,
    private val accessTokenProvider: () -> String
) : ReminderSyncApi {
    fun syncSettings(settings: ReminderSettings) {
        request("PUT", "/v1/autonomy", JSONObject()
            .put("autonomyEnabled", settings.autonomyEnabled)
            .put("proactiveSpeechEnabled", settings.proactiveSpeechEnabled)
            .put("quietHoursEnabled", settings.quietHoursEnabled)
            .put("quietHoursStart", settings.quietHoursStart)
            .put("quietHoursEnd", settings.quietHoursEnd))
    }

    fun create(reminder: ReminderRecord) {
        request("POST", "/v1/reminders", reminder.toBackendJson())
    }

    fun update(reminder: ReminderRecord) {
        request("PATCH", "/v1/reminders/${reminder.id}", reminder.toBackendJson())
    }

    /** Upsert with a stable local ID, making retries safe when a response was lost in transit. */
    fun sync(reminder: ReminderRecord) = syncSnapshot(reminder.toBackendJson().toString())

    override fun syncSnapshot(payload: String) {
        val snapshot = JSONObject(payload)
        try {
            request("PATCH", "/v1/reminders/${snapshot.getString("id")}", snapshot)
        } catch (error: IOException) {
            if (!Regex("HTTP 404\\.").containsMatchIn(error.message.orEmpty())) throw error
            request("POST", "/v1/reminders", snapshot)
        }
    }

    override fun delete(id: String) {
        try {
            request("DELETE", "/v1/reminders/$id", null)
        } catch (error: IOException) {
            // A retry after an ambiguous network failure is safe if the record is already absent.
            if (!Regex("HTTP 404\\.").containsMatchIn(error.message.orEmpty())) throw error
        }
    }

    private fun request(method: String, path: String, body: JSONObject?) {
        if (endpoint.isBlank()) return
        val connection = (URL(endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Accept", "application/json")
            accessTokenProvider().takeIf(String::isNotBlank)?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw IOException("Reminder sync failed with HTTP ${connection.responseCode}.")
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }
}

internal fun ReminderRecord.toBackendJson() = JSONObject()
    .put("id", id)
    .put("message", message)
    .put("scheduledAt", timestamp(scheduledAtMillis))
    .put("nextScheduledAt", timestamp(nextAtMillis))
    .put("lastDeliveredAt", lastDeliveredAtMillis?.let(::timestamp) ?: JSONObject.NULL)
    .put("timeZone", timeZoneId)
    .put("recurrence", recurrence)
    .put("enabled", status == "scheduled")
    .put("status", when (status) {
        "paused", "needs_attention" -> "paused"
        "completed", "delivered" -> "completed"
        else -> "scheduled"
    })

private fun timestamp(millis: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(millis)
