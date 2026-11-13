package com.companion.assistant

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar
import java.util.Locale

internal enum class LocalActionStatus { SUCCESS, NEEDS_CLARIFICATION, UNSUPPORTED, FAILED }
internal data class LocalActionResult(
    val status: LocalActionStatus,
    val message: String,
    val afterSpeech: (() -> String?)? = null
)

internal sealed class NativeAlarmCommand {
    data class Create(val hour: Int, val minute: Int, val days: List<Int>, val tomorrow: Boolean) : NativeAlarmCommand()
    data object Show : NativeAlarmCommand()
    data object Dismiss : NativeAlarmCommand()
    data object Snooze : NativeAlarmCommand()
    data object Manage : NativeAlarmCommand()
    data class Clarify(val message: String) : NativeAlarmCommand()
}

/** Deterministic Turkish alarm parser. Alarm requests never become companion reminders. */
internal object TurkishAlarmCommandParser {
    private val hourPattern = Regex("(?:saat\\s+)?(\\d{1,2})(?:\\s*[:.]\\s*(\\d{1,2}))?(?:\\s*['’ʼ´]?(?:ye|ya|e|a|de|da|te|ta))?")
    private val numberWords = mapOf(
        "sıfır" to 0, "bir" to 1, "iki" to 2, "üç" to 3, "dört" to 4, "beş" to 5,
        "altı" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9, "on" to 10,
        "onbir" to 11, "oniki" to 12, "onüç" to 13, "ondört" to 14, "onbeş" to 15,
        "onaltı" to 16, "onyedi" to 17, "onsekiz" to 18, "ondokuz" to 19,
        "yirmi" to 20, "otuz" to 30, "kırk" to 40, "elli" to 50
    )
    private val unitWords = numberWords.filterValues { it in 1..9 }.mapValues { it.value }
    private val tokenPattern = Regex("[\\p{L}\\p{N}]+")

    fun normalize(raw: String): String = raw
        .lowercase(Locale("tr", "TR"))
        .replace(Regex("[’‘ʼ´`]"), "'")
        .replace(Regex("[,!?;]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .removePrefix("ayşe ")
        .removePrefix("ayşe")
        .trim()

    fun parse(raw: String): NativeAlarmCommand? {
        val text = normalize(raw)
        if (!text.contains("alarm")) return null
        if (text.contains("hangi alarm") || text.contains("alarmlar") || text.contains("alarm list")) return NativeAlarmCommand.Show
        if (text.contains("ertele")) return NativeAlarmCommand.Snooze
        if (text.contains("kapat") || text.contains("sustur")) return NativeAlarmCommand.Dismiss
        if (text.contains("sil") || text.contains("değiştir") || text.contains("düzenle") || text.contains("etkinleştir")) return NativeAlarmCommand.Manage
        if (!text.contains("kur") && !text.contains("ayarla")) return NativeAlarmCommand.Show

        val parsedTime = findNumericTime(text) ?: findSpokenTime(text)
            ?: return NativeAlarmCommand.Clarify("Alarmı hangi saate kurmamı istersin?")
        val givenHour = parsedTime.first
        val minute = parsedTime.second
        if (givenHour !in 0..23 || minute !in 0..59) return NativeAlarmCommand.Clarify("Geçerli bir alarm saati söyler misin?")
        val morning = text.contains("sabah") || text.contains("öğleden önce")
        val evening = text.contains("akşam") || text.contains("öğleden sonra") || text.contains("gece")
        val matchedNumericHour = hourPattern.find(text)?.groupValues?.get(1).orEmpty()
        val has24HourNotation = givenHour >= 13 || matchedNumericHour.startsWith('0')
        if (givenHour in 1..12 && !morning && !evening && !has24HourNotation) {
            return NativeAlarmCommand.Clarify("Saat $givenHour için sabah mı akşam mı demek istedin?")
        }
        if (morning && evening) return NativeAlarmCommand.Clarify("Alarmın sabah mı akşam mı olacağını netleştirir misin?")
        val hour = when {
            text.contains("gece") && givenHour == 12 -> 0
            evening && givenHour in 1..11 -> givenHour + 12
            morning && givenHour == 12 -> 0
            else -> givenHour
        }
        val days = when {
            text.contains("hafta içi") -> listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
            text.contains("her gün") -> listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY)
            else -> emptyList()
        }
        return NativeAlarmCommand.Create(hour, minute, days, text.contains("yarın"))
    }

    private fun findNumericTime(text: String): Pair<Int, Int>? {
        val match = hourPattern.find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        return hour to minute
    }

    private fun findSpokenTime(text: String): Pair<Int, Int>? {
        val tokens = tokenPattern.findAll(text).map { it.value }.toList()
        val anchors = setOf("saat", "sabah", "akşam", "gece", "öğleden")
        for (index in tokens.indices) {
            if (tokens[index] !in anchors) continue
            var numberIndex = index + 1
            if (tokens.getOrNull(numberIndex) == "önce" || tokens.getOrNull(numberIndex) == "sonra") numberIndex++
            if (tokens.getOrNull(numberIndex) == "saat") numberIndex++
            val hourValue = spokenNumberAt(tokens, numberIndex) ?: continue
            val (hour, consumed) = hourValue
            val minuteValue = spokenNumberAt(tokens, numberIndex + consumed)
            val minute = minuteValue?.first?.takeIf { it in 0..59 } ?: 0
            return hour to minute
        }
        return null
    }

    private fun spokenNumberAt(tokens: List<String>, index: Int): Pair<Int, Int>? {
        val first = tokens.getOrNull(index)?.let(numberWords::get) ?: return null
        if (first in setOf(20, 30, 40, 50)) {
            val unit = tokens.getOrNull(index + 1)?.let(unitWords::get)
            if (unit != null) return (first + unit) to 2
        }
        if (first == 10) {
            val unit = tokens.getOrNull(index + 1)?.let(unitWords::get)
            if (unit != null) return (first + unit) to 2
        }
        return first to 1
    }
}

internal object NativeAlarmIntents {
    data class Plan(val action: String, val hour: Int? = null, val minute: Int? = null,
                    val days: List<Int> = emptyList(), val skipUi: Boolean = false)

    fun planFor(command: NativeAlarmCommand): Plan? = when (command) {
        is NativeAlarmCommand.Create -> Plan(AlarmClock.ACTION_SET_ALARM, command.hour, command.minute, command.days, true)
        NativeAlarmCommand.Show, NativeAlarmCommand.Manage, NativeAlarmCommand.Dismiss -> Plan(AlarmClock.ACTION_SHOW_ALARMS)
        NativeAlarmCommand.Snooze -> Plan(AlarmClock.ACTION_SNOOZE_ALARM)
        is NativeAlarmCommand.Clarify -> null
    }

    fun forCommand(command: NativeAlarmCommand): Intent? {
        val plan = planFor(command) ?: return null
        return forPlan(plan)
    }

    fun forPlan(plan: Plan): Intent = Intent(plan.action).apply {
            plan.hour?.let { putExtra(AlarmClock.EXTRA_HOUR, it) }
            plan.minute?.let { putExtra(AlarmClock.EXTRA_MINUTES, it) }
            if (plan.days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(plan.days))
            if (plan.action == AlarmClock.ACTION_SET_ALARM) putExtra(AlarmClock.EXTRA_MESSAGE, "Ayşe alarmı")
            if (plan.skipUi) putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    }
}

internal interface NativeAlarmLauncher {
    /** Returns the resolved Clock component, or null when no installed app handles the request. */
    fun resolve(plan: NativeAlarmIntents.Plan): String?
    /** Returns only after Android accepts startActivity; throws if it rejects the launch. */
    fun launch(plan: NativeAlarmIntents.Plan)
}

internal interface NativeAlarmDiagnostics {
    fun info(message: String)
    fun warning(message: String)
}

private object AndroidNativeAlarmDiagnostics : NativeAlarmDiagnostics {
    override fun info(message: String) { Log.i("NativeAlarm", message) }
    override fun warning(message: String) { Log.w("NativeAlarm", message) }
}

private class AndroidNativeAlarmLauncher(context: Context) : NativeAlarmLauncher {
    private val appContext = context.applicationContext

    override fun resolve(plan: NativeAlarmIntents.Plan): String? =
        NativeAlarmIntents.forPlan(plan).resolveActivity(appContext.packageManager)?.flattenToShortString()

    override fun launch(plan: NativeAlarmIntents.Plan) {
        appContext.startActivity(NativeAlarmIntents.forPlan(plan).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Uses only public Clock intents; the clock application owns the alarm data. */
internal class NativeAlarmCommandProcessor(
    private val launcher: NativeAlarmLauncher,
    private val nowCalendar: () -> Calendar = Calendar::getInstance,
    private val diagnostics: NativeAlarmDiagnostics = AndroidNativeAlarmDiagnostics
) {
    constructor(context: Context) : this(AndroidNativeAlarmLauncher(context.applicationContext))

    fun process(transcript: String, interactionId: String = "unknown"): LocalActionResult? {
        val command = TurkishAlarmCommandParser.parse(transcript) ?: return null
        val safeTranscript = LocalCommandDiagnostics.safeTranscript(transcript)
        val normalized = LocalCommandDiagnostics.safeTranscript(TurkishAlarmCommandParser.normalize(transcript))
        diagnostics.info("local_command_candidate interactionId=$interactionId transcript=\"$safeTranscript\" normalized=\"$normalized\"")
        diagnostics.info("local_command_selected interactionId=$interactionId intent=${command.javaClass.simpleName}")
        if (command is NativeAlarmCommand.Clarify) {
            diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm result=clarification")
            return LocalActionResult(LocalActionStatus.NEEDS_CLARIFICATION, command.message)
        }
        if (command is NativeAlarmCommand.Create && command.tomorrow && command.days.isEmpty()) {
            val now = nowCalendar()
            val target = nowCalendar().apply {
                timeInMillis = now.timeInMillis
                set(Calendar.HOUR_OF_DAY, command.hour)
                set(Calendar.MINUTE, command.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.after(now)) {
                diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm result=tomorrow_requires_clock_confirmation")
                return LocalActionResult(LocalActionStatus.NEEDS_CLARIFICATION,
                    "Saat uygulaması yarın için tarihi doğrudan seçemiyor. Yanlışlıkla bugüne kurmamak için Saat uygulamasında yarını seçmelisin.")
            }
        }
        if (command is NativeAlarmCommand.Create) {
            diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm action=create hour=${command.hour} minute=${command.minute} days=${command.days.size} tomorrow=${command.tomorrow}")
        } else {
            diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm action=${command.javaClass.simpleName}")
        }
        val plan = NativeAlarmIntents.planFor(command)
            ?: return LocalActionResult(LocalActionStatus.FAILED, "Alarm isteği hazırlanamadı.")
        val target = try {
            launcher.resolve(plan)
        } catch (error: RuntimeException) {
            diagnostics.warning("alarm_intent_resolution_failed interactionId=$interactionId action=${plan.action} type=${error.javaClass.simpleName}")
            return LocalActionResult(LocalActionStatus.FAILED, "Saat uygulaması alarm isteğini denetleyemedi.")
        }
        if (target == null) {
            diagnostics.warning("alarm_intent_unhandled interactionId=$interactionId action=${plan.action}")
            return LocalActionResult(LocalActionStatus.UNSUPPORTED, "Bu telefonda bu alarm işlemini destekleyen bir Saat uygulaması bulamadım.")
        }
        diagnostics.info("alarm_intent_constructed interactionId=$interactionId action=${plan.action} target=$target hour=${commandHour(command)} minute=${commandMinute(command)}")
        if (command == NativeAlarmCommand.Show || command == NativeAlarmCommand.Manage || command == NativeAlarmCommand.Dismiss) {
            val explanation = if (command == NativeAlarmCommand.Dismiss)
                "Hangi alarmın çaldığını uygulamadan doğrulayamıyorum. Saat ekranında kapatacağın alarmı seç."
            else "Saat uygulamasındaki alarmları açıyorum. Değiştirmek istediğin alarmı orada seç."
            return LocalActionResult(if (command == NativeAlarmCommand.Dismiss) LocalActionStatus.UNSUPPORTED else LocalActionStatus.SUCCESS,
                explanation, afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
        }
        return when (command) {
            is NativeAlarmCommand.Create -> {
                val displayTime = String.format(Locale("tr", "TR"), "%02d.%02d", command.hour, command.minute)
                LocalActionResult(LocalActionStatus.SUCCESS,
                    "Saat uygulamasında $displayTime alarmını kurma isteğini gönderiyorum. Açıldığında alarmı doğrulayabilirsin.",
                    afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
            }
            NativeAlarmCommand.Snooze -> LocalActionResult(LocalActionStatus.SUCCESS,
                "Çalan alarm varsa erteleme isteğini Saat uygulamasına iletiyorum.",
                afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
            else -> LocalActionResult(LocalActionStatus.SUCCESS,
                "Saat uygulamasındaki alarmları açıyorum. Değiştirmek istediğin alarmı orada seç.",
                afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
        }
    }

    private fun launchAfterSpeech(plan: NativeAlarmIntents.Plan, interactionId: String, target: String): String? = try {
        launcher.launch(plan)
        diagnostics.info("alarm_intent_launch_accepted interactionId=$interactionId action=${plan.action} target=$target")
        null
    } catch (error: RuntimeException) {
        diagnostics.warning("alarm_intent_launch_failed interactionId=$interactionId action=${plan.action} type=${error.javaClass.simpleName}")
        "Saat uygulaması alarm isteğini açamadı. Lütfen uygulamadaki Saat ayarlarını kontrol et."
    }

    private fun commandHour(command: NativeAlarmCommand): Int? = (command as? NativeAlarmCommand.Create)?.hour
    private fun commandMinute(command: NativeAlarmCommand): Int? = (command as? NativeAlarmCommand.Create)?.minute
}
