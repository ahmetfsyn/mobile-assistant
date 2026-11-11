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
) {
    fun syncSettings(settings: ReminderSettings) {
        request("PUT", "/v1/autonomy", JSONObject()
            .put("autonomyEnabled", settings.autonomyEnabled)
            .put("proactiveSpeechEnabled", settings.proactiveSpeechEnabled)
            .put("quietHoursEnabled", settings.quietHoursEnabled)
            .put("quietHoursStart", settings.quietHoursStart)
            .put("quietHoursEnd", settings.quietHoursEnd))
    }

    fun create(reminder: ReminderRecord) {
        request("POST", "/v1/reminders", reminderJson(reminder))
    }

    fun update(reminder: ReminderRecord) {
        request("PATCH", "/v1/reminders/${reminder.id}", reminderJson(reminder))
    }

    fun delete(id: String) {
        request("DELETE", "/v1/reminders/$id", null)
    }

    private fun reminderJson(reminder: ReminderRecord) = JSONObject()
        .put("id", reminder.id)
        .put("message", reminder.message)
        .put("scheduledAt", timestamp(reminder.scheduledAtMillis))
        .put("nextScheduledAt", timestamp(reminder.nextAtMillis))
        .put("lastDeliveredAt", reminder.lastDeliveredAtMillis?.let(::timestamp) ?: JSONObject.NULL)
        .put("timeZone", reminder.timeZoneId)
        .put("recurrence", reminder.recurrence)
        .put("enabled", reminder.status == "scheduled")
        .put("status", when (reminder.status) {
            "paused", "needs_attention" -> "paused"
            "completed", "delivered" -> "completed"
            else -> "scheduled"
        })

    private fun timestamp(millis: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(millis)

    private fun request(method: String, path: String, body: JSONObject?) {
        if (endpoint.isBlank()) return
        val base = endpoint.trimEnd('/')
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
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
