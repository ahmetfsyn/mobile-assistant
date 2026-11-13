package com.companion.assistant

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal enum class TurkishClockQuery { TIME, WEEKDAY, DATE }

internal enum class LocalCommandRoute { NATIVE_ALARM, DEVICE_CLOCK, WEATHER, COMPANION_REMINDER, CONVERSATION_BACKEND }

internal data class LocalCommandDispatchResult(
    val route: LocalCommandRoute,
    val result: LocalActionResult
)

/** Deterministic local handlers win; only an unmatched message is allowed to require the backend. */
internal object LocalCommandDispatcher {
    fun dispatch(
        alarm: LocalActionResult?,
        clock: LocalActionResult?,
        reminder: String?,
        weather: LocalActionResult? = null
    ): LocalCommandDispatchResult = when {
        alarm != null -> LocalCommandDispatchResult(LocalCommandRoute.NATIVE_ALARM, alarm)
        clock != null -> LocalCommandDispatchResult(LocalCommandRoute.DEVICE_CLOCK, clock)
        weather != null -> LocalCommandDispatchResult(LocalCommandRoute.WEATHER, weather)
        reminder != null -> LocalCommandDispatchResult(
            LocalCommandRoute.COMPANION_REMINDER,
            LocalActionResult(LocalActionStatus.LOCAL_SUCCESS, reminder)
        )
        else -> LocalCommandDispatchResult(
            LocalCommandRoute.CONVERSATION_BACKEND,
            LocalActionResult(LocalActionStatus.REMOTE_REQUIRED, "")
        )
    }
}

internal object LocalCommandRouteSelector {
    fun select(
        alarm: LocalActionResult?,
        clock: LocalActionResult?,
        reminder: String?,
        weather: LocalActionResult? = null
    ): LocalCommandRoute = LocalCommandDispatcher.dispatch(alarm, clock, reminder, weather).route
}

/** Recognizes only deterministic device-clock questions; these never go to the conversation backend. */
internal object TurkishClockQueryParser {
    private val turkish = Locale("tr", "TR")
    private val whitespace = Regex("\\s+")

    fun normalize(raw: String): String = raw
        .lowercase(turkish)
        .replace(Regex("[’‘ʼ´`＇]"), "'")
        .replace(Regex("[,!?;.]"), " ")
        .replace(whitespace, " ")
        .trim()
        .removePrefix("ayşe ")
        .removePrefix("ayşe")
        .trim()

    fun parse(raw: String): TurkishClockQuery? {
        val text = normalize(raw)
        if ("alarm" in text) return null
        val folded = text.foldTurkishMarks()
        if ("bugün günlerden ne" in text || "bugun gunlerden ne" in folded) return TurkishClockQuery.WEEKDAY
        if ("bugünün tarihi ne" in text || "bugunun tarihi ne" in folded ||
            "bugün tarih ne" in text || "bugun tarih ne" in folded
        ) return TurkishClockQuery.DATE
        if ("saat kaç" in text || "su an saat kac" in folded || "şu an saat kaç" in text ||
            "şu anda saat kaç" in text || "su anda saat kac" in folded
        ) return TurkishClockQuery.TIME
        return null
    }

    fun parseCorrection(previousTranscript: String, correctionTranscript: String): TurkishClockQuery? {
        val previous = parse(previousTranscript) ?: return null
        val correction = normalize(correctionTranscript)
        if ("hayır" !in correction && "hayir" !in correction && "değil" !in correction && "degil" !in correction) {
            return null
        }
        return when {
            previous == TurkishClockQuery.TIME && ("tarih" in correction || "gün" in correction || "gun" in correction) ->
                if ("gün" in correction || "gun" in correction) TurkishClockQuery.WEEKDAY else TurkishClockQuery.DATE
            previous == TurkishClockQuery.DATE && ("saat" in correction || "zaman" in correction) -> TurkishClockQuery.TIME
            previous == TurkishClockQuery.WEEKDAY && "tarih" in correction -> TurkishClockQuery.DATE
            else -> null
        }
    }
}

private fun String.foldTurkishMarks(): String = replace("ı", "i").replace("ğ", "g")
    .replace("ş", "s").replace("ü", "u").replace("ö", "o").replace("ç", "c")

/** Allows exact diagnostic text only for a small, fixed set of non-sensitive local commands. */
internal object LocalCommandDiagnostics {
    private val commandWords = setOf(
        "ayşe", "sabah", "saat", "alarm", "kur", "ayarla", "yarın", "gece", "akşam", "ogleden",
        "önce", "sonra", "öğleden", "şu", "su", "an", "anda", "kaç", "kac", "bugün", "bugun",
        "günlerden", "gunlerden", "ne", "bugünün", "bugunun", "tarihi", "tarih", "kaçta", "kacta",
        "sıfır", "sifir", "bir", "iki", "üç", "uc", "dört", "dort", "beş", "bes", "altı", "alti",
        "yedi", "sekiz", "dokuz", "on", "onbir", "oniki", "onüç", "ondört", "ondort", "onbeş",
        "onbes", "onaltı", "onalti", "onyedi", "onsekiz", "ondokuz", "yirmi", "otuz", "kırk", "kirk", "elli"
    )
    private val safeSuffixedNumber = Regex("\\d{1,2}(['’]?(?:e|a|ye|ya|de|da|te|ta))?")

    fun safeTranscript(transcript: String): String {
        val words = transcript.lowercase(Locale("tr", "TR")).split(Regex("[^\\p{L}\\p{N}'’]+"))
            .filter(String::isNotBlank)
        return if (words.size <= 14 && words.all { it in commandWords || safeSuffixedNumber.matches(it) }) {
            transcript.trim().take(160)
        } else {
            VoiceDiagnostics.redactedPreview(transcript)
        }
    }
}

/** Formats values from the Android device clock in Turkish, without network or backend access. */
internal object TurkishClockResponseFormatter {
    private val turkish = Locale("tr", "TR")
    private val units = arrayOf(
        "sıfır", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz",
        "on", "on bir", "on iki", "on üç", "on dört", "on beş", "on altı", "on yedi",
        "on sekiz", "on dokuz"
    )
    private val tens = arrayOf("", "", "yirmi", "otuz", "kırk", "elli")

    fun response(query: TurkishClockQuery, nowMillis: Long, timeZone: TimeZone): String {
        val calendar = Calendar.getInstance(timeZone, turkish).apply { timeInMillis = nowMillis }
        return when (query) {
            TurkishClockQuery.TIME -> {
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                val time = if (minute == 0) number(hour) else "${number(hour)} ${number(minute)}"
                "Şu anda saat $time."
            }
            TurkishClockQuery.WEEKDAY -> {
                val day = SimpleDateFormat("EEEE", turkish).apply { this.timeZone = timeZone }.format(calendar.time).lowercase(turkish)
                "Bugün günlerden $day."
            }
            TurkishClockQuery.DATE -> {
                val date = SimpleDateFormat("d MMMM yyyy", turkish).apply { this.timeZone = timeZone }.format(calendar.time)
                "Bugünün tarihi $date."
            }
        }
    }

    internal fun number(value: Int): String {
        if (value !in 0..59) return value.toString()
        if (value < 20) return units[value]
        val ten = value / 10
        val unit = value % 10
        return if (unit == 0) tens[ten] else "${tens[ten]} ${units[unit]}"
    }
}

internal class LocalClockCommandProcessor(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timeZone: () -> TimeZone = TimeZone::getDefault
) {
    fun process(
        transcript: String,
        interactionId: String = "unknown",
        correctionContext: String? = null
    ): LocalActionResult? {
        val query = TurkishClockQueryParser.parse(transcript)
            ?: correctionContext?.let { TurkishClockQueryParser.parseCorrection(it, transcript) }
            ?: return null
        val normalized = TurkishClockQueryParser.normalize(transcript)
        Log.i(TAG, "local_command_candidate interactionId=$interactionId transcript=\"${LocalCommandDiagnostics.safeTranscript(transcript)}\" normalized=\"${LocalCommandDiagnostics.safeTranscript(normalized)}\"")
        Log.i(TAG, "local_command_selected interactionId=$interactionId intent=clock.${query.name.lowercase(Locale.US)}")
        val response = TurkishClockResponseFormatter.response(query, nowMillis(), timeZone())
        Log.i(TAG, "local_handler_called interactionId=$interactionId handler=device_clock result=${query.name.lowercase(Locale.US)} tz=${timeZone().id}")
        return LocalActionResult(LocalActionStatus.LOCAL_SUCCESS, response)
    }

    private companion object { const val TAG = "LocalCommand" }
}
