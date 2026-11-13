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
    val afterSpeech: (() -> Unit)? = null
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
    private val hourPattern = Regex("(?:saat\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?(?:['’]?(?:e|a|ye|ya|de|da))?")

    fun parse(raw: String): NativeAlarmCommand? {
        val text = raw.lowercase(Locale("tr", "TR"))
        if (!text.contains("alarm")) return null
        if (text.contains("hangi alarm") || text.contains("alarmlar") || text.contains("alarm list")) return NativeAlarmCommand.Show
        if (text.contains("ertele")) return NativeAlarmCommand.Snooze
        if (text.contains("kapat") || text.contains("sustur")) return NativeAlarmCommand.Dismiss
        if (text.contains("sil") || text.contains("değiştir") || text.contains("düzenle") || text.contains("etkinleştir")) return NativeAlarmCommand.Manage
        if (!text.contains("kur") && !text.contains("ayarla")) return NativeAlarmCommand.Show

        val found = hourPattern.find(text) ?: return NativeAlarmCommand.Clarify("Alarmı hangi saate kurmamı istersin?")
        val givenHour = found.groupValues[1].toIntOrNull() ?: return NativeAlarmCommand.Clarify("Alarm saatini anlayamadım.")
        val minute = found.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return NativeAlarmCommand.Clarify("Alarm dakikasını anlayamadım.")
        if (givenHour !in 0..23 || minute !in 0..59) return NativeAlarmCommand.Clarify("Geçerli bir alarm saati söyler misin?")
        val morning = text.contains("sabah") || text.contains("öğleden önce")
        val evening = text.contains("akşam") || text.contains("öğleden sonra")
        val has24HourNotation = givenHour >= 13 || found.groupValues[1].startsWith('0') || found.groupValues[2].isNotEmpty()
        if (givenHour in 1..12 && !morning && !evening && !has24HourNotation) {
            return NativeAlarmCommand.Clarify("Saat $givenHour için sabah mı akşam mı demek istedin?")
        }
        if (morning && evening) return NativeAlarmCommand.Clarify("Alarmın sabah mı akşam mı olacağını netleştirir misin?")
        val hour = when {
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
        return Intent(plan.action).apply {
            plan.hour?.let { putExtra(AlarmClock.EXTRA_HOUR, it) }
            plan.minute?.let { putExtra(AlarmClock.EXTRA_MINUTES, it) }
            if (plan.days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(plan.days))
            if (plan.action == AlarmClock.ACTION_SET_ALARM) putExtra(AlarmClock.EXTRA_MESSAGE, "Ayşe alarmı")
            if (plan.skipUi) putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
    }
}

/** Uses only public Clock intents; the clock application owns the alarm data. */
internal class NativeAlarmCommandProcessor(context: Context) {
    private val appContext = context.applicationContext

    fun process(transcript: String): LocalActionResult? {
        val command = TurkishAlarmCommandParser.parse(transcript) ?: return null
        if (command is NativeAlarmCommand.Clarify) return LocalActionResult(LocalActionStatus.NEEDS_CLARIFICATION, command.message)
        if (command is NativeAlarmCommand.Create && command.tomorrow && command.days.isEmpty()) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, command.hour)
                set(Calendar.MINUTE, command.minute)
                set(Calendar.SECOND, 0)
            }
            if (target.after(now)) return LocalActionResult(LocalActionStatus.NEEDS_CLARIFICATION,
                "Saat uygulaması belirli bir tarihi doğrudan seçemiyor. Yarın için bu alarmı Saat ekranında kontrol ederek kurmalısın.")
        }
        val intent = NativeAlarmIntents.forCommand(command) ?: return LocalActionResult(LocalActionStatus.FAILED, "Alarm isteği hazırlanamadı.")
        if (intent.resolveActivity(appContext.packageManager) == null) {
            return LocalActionResult(LocalActionStatus.UNSUPPORTED, "Bu telefonda bu alarm işlemini destekleyen bir Saat uygulaması bulamadım.")
        }
        if (command == NativeAlarmCommand.Show || command == NativeAlarmCommand.Manage || command == NativeAlarmCommand.Dismiss) {
            val explanation = if (command == NativeAlarmCommand.Dismiss)
                "Hangi alarmın çaldığını uygulamadan doğrulayamıyorum. Saat ekranında kapatacağın alarmı seç."
            else "Saat uygulamasındaki alarmları açıyorum. Değiştirmek istediğin alarmı orada seç."
            return LocalActionResult(if (command == NativeAlarmCommand.Dismiss) LocalActionStatus.UNSUPPORTED else LocalActionStatus.SUCCESS,
                explanation, afterSpeech = {
                    runCatching { appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Log.w(TAG, "native_alarm_list_failed type=${it.javaClass.simpleName}") }
            })
        }
        return try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "native_alarm_intent_dispatched action=${intent.action}")
            when (command) {
                is NativeAlarmCommand.Create -> LocalActionResult(LocalActionStatus.SUCCESS,
                    "${String.format(Locale("tr", "TR"), "%02d.%02d", command.hour, command.minute)} için sistem Saat uygulamasına alarm kurma isteği gönderdim. Alarmı Saat uygulamasında doğrula.")
                NativeAlarmCommand.Snooze -> LocalActionResult(LocalActionStatus.SUCCESS,
                    "Çalan alarm varsa erteleme isteğini Saat uygulamasına gönderdim.")
                else -> LocalActionResult(LocalActionStatus.SUCCESS,
                    "Saat uygulamasındaki alarmları açtım. Değiştirmek istediğin alarmı orada seç.")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "native_alarm_intent_failed action=${intent.action} type=${error.javaClass.simpleName}")
            LocalActionResult(LocalActionStatus.FAILED, "Saat uygulaması alarm isteğini açamadı.")
        }
    }

    private companion object { const val TAG = "NativeAlarm" }
}
