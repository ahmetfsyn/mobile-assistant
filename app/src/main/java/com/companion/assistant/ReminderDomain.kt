package com.companion.assistant

import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal data class ReminderSettings(
    val autonomyEnabled: Boolean = false,
    val proactiveSpeechEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = true,
    val quietHoursStart: String = "23:00",
    val quietHoursEnd: String = "08:00"
)

internal data class ReminderRecord(
    val id: String,
    val message: String,
    val scheduledAtMillis: Long,
    val timeZoneId: String,
    val recurrence: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastDeliveredAtMillis: Long?,
    val nextAtMillis: Long,
    val pendingAtMillis: Long?,
    val attempts: Int,
    val deliveryState: String
)

internal sealed class ReminderCommand {
    data class Create(val message: String, val atMillis: Long, val recurrence: String) : ReminderCommand()
    data class Clarify(val question: String) : ReminderCommand()
    data object List : ReminderCommand()
    data object DisableAll : ReminderCommand()
    data object EnableAll : ReminderCommand()
    data class Delete(val query: String) : ReminderCommand()
    data class Pause(val query: String) : ReminderCommand()
    data class Complete(val query: String) : ReminderCommand()
    data class Snooze(val query: String, val delayMillis: Long) : ReminderCommand()
}

/** Deterministic, bounded Turkish command parser. It never asks an AI to guess a schedule. */
internal object TurkishReminderCommandParser {
    private val relativePattern = Regex("\\b(\\d+|bir|iki|üç|dört|beş|on|yarım)\\s+(dakika|saat)\\s+sonra\\b", RegexOption.IGNORE_CASE)
    private val hourPattern = Regex("\\bsaat\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(?:['’]?(?:da|de|ta|te))?", RegexOption.IGNORE_CASE)
    private val suffixedHourPattern = Regex("\\b(\\d{1,2})['’](?:da|de|ta|te)\\b", RegexOption.IGNORE_CASE)
    private val clockPattern = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
    private val explicitScheduleAction = Regex("\\b(?:hatırlat|hatırlatabilir misin|hatırlatır mısın|hatırlatıcı oluştur|hatırlatıcı ekle|hatırlatıcı kur|uyar|selamla|kontrol et)\\b")

    fun parse(text: String, nowMillis: Long, timeZoneId: String): ReminderCommand? {
        val normalized = text.trim().lowercase(Locale("tr", "TR"))
        if (normalized.isEmpty() || normalized.length > 500) return null
        if (Regex("\\b(?:hatırlatmaları|hatırlatıcıları)\\s+(?:kapat|durdur)\\b").containsMatchIn(normalized)) {
            return ReminderCommand.DisableAll
        }
        if (Regex("\\b(?:hatırlatmaları|hatırlatıcıları)\\s+(?:tekrar\\s+)?aç\\b").containsMatchIn(normalized)) return ReminderCommand.EnableAll
        if ((normalized.contains("hatırlatmalar") || normalized.contains("hatırlatıcılar")) &&
            (normalized.contains("neler") || normalized.contains("listele") || normalized.contains("hangileri") || normalized.contains("bugünkü"))
        ) return ReminderCommand.List
        if (normalized.contains("hatırlat") && Regex("\\b(?:sil|kaldır)\\b").containsMatchIn(normalized)) {
            return ReminderCommand.Delete(extractTarget(normalized))
        }
        if (normalized.contains("hatırlat") && Regex("\\b(?:tamamla|tamamlandı)\\b").containsMatchIn(normalized)) {
            return ReminderCommand.Complete(extractTarget(normalized))
        }
        if (normalized.contains("hatırlat") && Regex("\\bertele\\b").containsMatchIn(normalized)) {
            val delay = Regex("\\b(\\d+|bir|iki|üç|dört|beş|on)\\s+(dakika|saat)\\b").find(normalized)?.let { match ->
                parseNumber(match.groupValues[1]) * if (match.groupValues[2] == "dakika") 60_000L else 3_600_000L
            } ?: return ReminderCommand.Clarify("Kaç dakika erteleyeyim?")
            return ReminderCommand.Snooze(extractTarget(normalized), delay)
        }
        if (normalized.contains("hatırlat") && Regex("\\bduraklat\\b").containsMatchIn(normalized)) {
            return ReminderCommand.Pause(extractTarget(normalized))
        }

        if (!explicitScheduleAction.containsMatchIn(normalized)) return null

        val zone = TimeZone.getTimeZone(timeZoneId)
        val recurrence = recurrenceFor(normalized)
        val relative = relativePattern.find(normalized)
        if (relative != null && recurrence != "none") {
            return ReminderCommand.Clarify("Tekrarlanan hatırlatma için her gün aynı saati söyler misin?")
        }
        val atMillis = if (relative != null) {
            val count = parseNumber(relative.groupValues[1])
            val amount = when (relative.groupValues[2]) {
                "dakika" -> count * 60_000L
                else -> count * 3_600_000L
            }
            if (amount <= 0L || amount > 366L * 24 * 60 * 60 * 1000) {
                return ReminderCommand.Clarify("Ne zaman hatırlatmamı istersin? Lütfen net bir zaman söyle.")
            }
            nowMillis + amount
        } else {
            val time = parseClock(normalized) ?: return ReminderCommand.Clarify(
                "Saati netleştirir misin? Örneğin yarın saat 10'da diyebilirsin."
            )
            val calendar = Calendar.getInstance(zone).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, time.first)
                set(Calendar.MINUTE, time.second)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            when {
                normalized.contains("yarından sonra") -> calendar.add(Calendar.DAY_OF_YEAR, 2)
                normalized.contains("yarın") -> calendar.add(Calendar.DAY_OF_YEAR, 1)
                normalized.contains("bugün") -> Unit
                recurrence != "none" -> moveToNextRecurrence(calendar, recurrence, nowMillis)
                calendar.timeInMillis <= nowMillis -> return ReminderCommand.Clarify(
                    "Bu saat bugün geçti. Bugün mü, yarın mı hatırlatayım?"
                )
            }
            if (calendar.timeInMillis <= nowMillis && recurrence == "none") {
                return ReminderCommand.Clarify("Bu saat geçmiş görünüyor. Hangi gün için ayarlayayım?")
            }
            calendar.timeInMillis
        }
        if (atMillis <= nowMillis) return ReminderCommand.Clarify("Hatırlatma zamanı geçmiş görünüyor. Yeni bir zaman söyler misin?")
        val message = extractMessage(normalized).ifBlank {
            if (normalized.contains("selamla")) "Sana selam vereyim" else "Hatırlatma"
        }.take(240)
        return ReminderCommand.Create(message, atMillis, recurrence)
    }

    private fun parseClock(text: String): Pair<Int, Int>? {
        val match = clockPattern.find(text) ?: hourPattern.find(text) ?: suffixedHourPattern.find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val adjustedHour = when {
            text.contains("akşam") && hour in 1..11 -> hour + 12
            text.contains("öğleden sonra") && hour in 1..11 -> hour + 12
            text.contains("sabah") && hour == 12 -> 0
            else -> hour
        }
        return adjustedHour to minute
    }

    private fun recurrenceFor(text: String): String = when {
        text.contains("her gün") || text.contains("her sabah") || text.contains("her akşam") -> "daily"
        text.contains("hafta içi") -> "weekdays"
        else -> listOf("pazartesi", "salı", "çarşamba", "perşembe", "cuma", "cumartesi", "pazar")
            .firstOrNull(text::contains)?.let { day ->
                "weekly:" + mapOf("pazartesi" to "MO", "salı" to "TU", "çarşamba" to "WE", "perşembe" to "TH", "cuma" to "FR", "cumartesi" to "SA", "pazar" to "SU").getValue(day)
            } ?: "none"
    }

    private fun moveToNextRecurrence(calendar: Calendar, recurrence: String, now: Long) {
        while (calendar.timeInMillis <= now || !matchesRecurrence(calendar, recurrence)) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun matchesRecurrence(calendar: Calendar, recurrence: String): Boolean = when (recurrence) {
        "daily" -> true
        "weekdays" -> calendar.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        "weekly:MO" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
        "weekly:TU" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.TUESDAY
        "weekly:WE" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY
        "weekly:TH" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.THURSDAY
        "weekly:FR" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        "weekly:SA" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
        "weekly:SU" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        else -> true
    }

    private fun extractMessage(text: String): String = text
        .replace(relativePattern, " ")
        .replace(hourPattern, " ")
        .replace(suffixedHourPattern, " ")
        .replace(clockPattern, " ")
        .replace(Regex("\\b(yarından sonra|yarın|bugün|her gün|her sabah|her akşam|hafta içi|pazartesi|salı|çarşamba|perşembe|cuma|cumartesi|pazar|sabah|akşam|öğleden sonra|saat|sonra|beni|bana|hatırlatabilir misin|hatırlatır mısın|hatırlat|hatırlatıcı|oluştur|ekle|kur|uyar|selamla|kontrol et|lütfen)\\b"), " ")
        .replace(Regex("['’]?(?:da|de|ta|te)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', ',', '?', '!')

    private fun extractTarget(text: String): String = text
        .replace(relativePattern, " ")
        .replace(Regex("\\b\\d+\\s+(?:dakika|saat)\\b"), " ")
        .replace(Regex("hatırlat(?:mayı|masını|mayı|ma)?|hatırlatıcı(?:yı|sını)?|sil|kaldır|ertele|duraklat|tamamla|tamamlandı|lütfen"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseNumber(value: String): Long = when (value) {
        "bir" -> 1L
        "iki" -> 2L
        "üç" -> 3L
        "dört" -> 4L
        "beş" -> 5L
        "on" -> 10L
        "yarım" -> 30L
        else -> value.toLongOrNull() ?: 0L
    }
}

internal object ReminderTimePolicy {
    fun nextOccurrence(baseMillis: Long, recurrence: String, zoneId: String, afterMillis: Long): Long? {
        if (recurrence == "none") return null
        val zone = TimeZone.getTimeZone(zoneId)
        val candidate = Calendar.getInstance(zone).apply { timeInMillis = baseMillis }
        do {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        } while (candidate.timeInMillis <= afterMillis || !matches(candidate, recurrence))
        return candidate.timeInMillis
    }

    fun nextQuietEnd(nowMillis: Long, settings: ReminderSettings, zoneId: String): Long {
        val zone = TimeZone.getTimeZone(zoneId)
        val start = parseMinutes(settings.quietHoursStart)
        val end = parseMinutes(settings.quietHoursEnd)
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        calendar.set(Calendar.HOUR_OF_DAY, end / 60)
        calendar.set(Calendar.MINUTE, end % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val currentMinutes = Calendar.getInstance(zone).run {
            timeInMillis = nowMillis
            get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
        }
        val crossesMidnight = start >= end
        if (crossesMidnight && currentMinutes >= start || (!crossesMidnight && currentMinutes >= end)) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (calendar.timeInMillis <= nowMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    fun isQuiet(nowMillis: Long, settings: ReminderSettings, zoneId: String): Boolean {
        if (!settings.quietHoursEnabled) return false
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply { timeInMillis = nowMillis }
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val start = parseMinutes(settings.quietHoursStart)
        val end = parseMinutes(settings.quietHoursEnd)
        return if (start == end) false else if (start < end) minute in start until end else minute >= start || minute < end
    }

    fun formatLocal(millis: Long, zoneId: String): String = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM, DateFormat.SHORT, Locale("tr", "TR")
    ).apply { timeZone = TimeZone.getTimeZone(zoneId) }.format(millis)

    private fun parseMinutes(value: String): Int {
        val pieces = value.split(":")
        return ((pieces.getOrNull(0)?.toIntOrNull() ?: 23) * 60 + (pieces.getOrNull(1)?.toIntOrNull() ?: 0)).coerceIn(0, 1439)
    }

    private fun matches(calendar: Calendar, recurrence: String): Boolean = when (recurrence) {
        "daily" -> true
        "weekdays" -> calendar.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        "weekly:MO" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
        "weekly:TU" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.TUESDAY
        "weekly:WE" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY
        "weekly:TH" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.THURSDAY
        "weekly:FR" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        "weekly:SA" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
        "weekly:SU" -> calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        else -> false
    }
}
