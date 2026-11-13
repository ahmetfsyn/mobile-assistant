package com.companion.assistant

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar
import java.util.Locale

internal enum class LocalActionStatus {
    LOCAL_SUCCESS,
    LOCAL_FAILURE,
    NEEDS_CLARIFICATION,
    UNSUPPORTED,
    REMOTE_REQUIRED,
    REMOTE_FAILURE,
    /** Android accepted an intent, but its Clock implementation exposes no result to verify state. */
    ACTION_DISPATCHED
}
internal data class LocalActionResult(
    val status: LocalActionStatus,
    val message: String,
    val afterSpeech: (() -> String?)? = null
)

internal sealed class NativeAlarmCommand {
    data class Create(val hour: Int, val minute: Int, val days: List<Int>, val tomorrow: Boolean) : NativeAlarmCommand()
    data class RelativeCreate(val durationMinutes: Int) : NativeAlarmCommand()
    data object Show : NativeAlarmCommand()
    data class ListAlarms(val enabledOnly: Boolean) : NativeAlarmCommand()
    data object EnableAll : NativeAlarmCommand()
    data class EnableOne(val target: AlarmTarget?) : NativeAlarmCommand()
    data object DisableAll : NativeAlarmCommand()
    data class DisableOne(val target: AlarmTarget?) : NativeAlarmCommand()
    data class DeleteAll(val enabledOnly: Boolean) : NativeAlarmCommand()
    data class DeleteOne(val target: AlarmTarget?) : NativeAlarmCommand()
    data class EditOne(val target: AlarmTarget?, val newHour: Int, val newMinute: Int) : NativeAlarmCommand()
    data object Dismiss : NativeAlarmCommand()
    data object Snooze : NativeAlarmCommand()
    data object Manage : NativeAlarmCommand()
    data class Clarify(val message: String) : NativeAlarmCommand()
}

internal data class AlarmTarget(
    val timeMinutes: Int? = null,
    val label: String? = null,
    val timeIsAmbiguous: Boolean = false
)

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
        .trimEnd('.')
        .trim()
        .removePrefix("ayşe ")
        .removePrefix("ayşe")
        .trim()

    fun parse(raw: String): NativeAlarmCommand? {
        val text = normalize(raw)
        val asksForAlarm = "alarm" in text || "uyandır" in text || "uyandir" in text
        if (!asksForAlarm) return null
        relativeDurationMinutes(text)?.let { return NativeAlarmCommand.RelativeCreate(it) }
        if (text.contains("ertele")) return NativeAlarmCommand.Snooze
        if ((text.contains("kapat") || text.contains("sustur")) &&
            (text.contains("çalan") || text.contains("calan") || text.contains("çalmakta") || text.contains("calmakta"))) {
            return NativeAlarmCommand.Dismiss
        }
        val all = listOf("bütün", "butun", "tüm", "tum", "hepsini", "hepsi").any(text::contains)
        val pluralAlarm = "alarmlar" in text || "alarmları" in text || "alarm listesi" in text
        val enabledOnly = "aktif" in text || "etkin" in text
        val target = alarmTarget(text)
        if (listOf("listele", "göster", "goster", "hangi alarm", "alarm listesi").any(text::contains)) {
            return NativeAlarmCommand.ListAlarms(enabledOnly)
        }
        if ("sil" in text) {
            if (all || (enabledOnly && pluralAlarm)) return NativeAlarmCommand.DeleteAll(enabledOnly)
            return if (target != null) NativeAlarmCommand.DeleteOne(target)
            else NativeAlarmCommand.Clarify("Hangi alarmı sileceğimi saatini veya etiketini söyleyerek belirtir misin?")
        }
        val explicitEdit = listOf("değiştir", "düzenle", "degistir", "duzenle").any(text::contains)
        val impliedEdit = Regex("\\b(?:al|ayarla)$").containsMatchIn(text) && extractTimes(text).size >= 2
        if (explicitEdit || impliedEdit) {
            val times = extractTimes(text)
            if (target != null && times.size >= 2) {
                val (hour, minute) = times.last()
                return NativeAlarmCommand.EditOne(target, resolveAlarmHour(hour, text), minute)
            }
            return NativeAlarmCommand.Clarify("Hangi alarmı hangi yeni saate alayım? Örneğin: saat 9 alarmını 10'a al.")
        }
        if (listOf("devre dışı bırak", "devre disi birak", "kapat").any(text::contains)) {
            if (all || (enabledOnly && pluralAlarm)) return NativeAlarmCommand.DisableAll
            return if (target != null) NativeAlarmCommand.DisableOne(target)
            else NativeAlarmCommand.Clarify("Hangi alarmı kapatayım? Saatini veya etiketini söyleyebilir misin?")
        }
        if (listOf("etkinleştir", "etkinlestir", "aç", "ac").any(text::contains)) {
            if (all) return NativeAlarmCommand.EnableAll
            return if (target != null) NativeAlarmCommand.EnableOne(target)
            else NativeAlarmCommand.Clarify("Hangi alarmı etkinleştireyim? Saatini veya etiketini söyleyebilir misin?")
        }
        if ("kapat" in text || "sustur" in text) return NativeAlarmCommand.Clarify(
            "Çalan alarmı mı susturayım, yoksa kayıtlı alarmları mı devre dışı bırakayım?"
        )
        if (!text.contains("kur") && !text.contains("ayarla") && !text.contains("çalsın") && !text.contains("calsin")) {
            return NativeAlarmCommand.ListAlarms(false)
        }

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

    private fun alarmTarget(text: String): AlarmTarget? {
        val times = extractTimes(text)
        val selectorTime = when {
            times.size >= 2 && ("düzenle" in text || "değiştir" in text || "duzenle" in text || "degistir" in text) -> times.first()
            times.isNotEmpty() -> times.first()
            else -> null
        }
        val label = Regex("([\\p{L}][\\p{L} -]{0,30})\\s+alarm(?:ını|ini|ını|ini|ı|i)")
            .find(text)?.groupValues?.get(1)?.trim()
            ?.takeUnless { candidate ->
                candidate.split(' ').any { it in setOf("saat", "aktif", "etkin", "bütün", "butun", "tüm", "tum") ||
                    it in setOf("sabah", "akşam", "gece", "öğleden") || it in numberWords.keys ||
                    it.startsWith("yarın") || it.startsWith("yarin") || it.startsWith("bugün") || it.startsWith("bugun") }
            }
        return when {
            selectorTime != null -> {
                val periodSpecified = listOf("sabah", "akşam", "gece", "öğleden önce", "öğleden sonra").any(text::contains)
                val resolvedHour = resolveAlarmHour(selectorTime.first, text)
                AlarmTarget(
                    timeMinutes = resolvedHour * 60 + selectorTime.second,
                    label = label,
                    timeIsAmbiguous = selectorTime.first in 1..12 && !periodSpecified
                )
            }
            label != null -> AlarmTarget(label = label)
            else -> null
        }
    }

    private fun resolveAlarmHour(hour: Int, text: String): Int = when {
        "gece" in text && hour == 12 -> 0
        ("akşam" in text || "gece" in text || "öğleden sonra" in text) && hour in 1..11 -> hour + 12
        ("sabah" in text || "öğleden önce" in text) && hour == 12 -> 0
        else -> hour
    }

    private fun extractTimes(text: String): List<Pair<Int, Int>> {
        val numericMatches = ArrayList<Triple<Int, Int, Int>>()
        val clockTime = Regex("(?<!\\d)(\\d{1,2})\\s*[:.]\\s*(\\d{2})(?!\\d)")
        clockTime.findAll(text).forEach { match ->
            numericMatches += Triple(match.groups[1]!!.range.first, match.groupValues[1].toIntOrNull() ?: return@forEach,
                match.groupValues[2].toIntOrNull() ?: return@forEach)
        }
        val contextual = Regex("(?:saat|sabah|akşam|gece|öğleden(?: önce| sonra)?)\\s*(\\d{1,2})(?!\\d)(?!\\s*[:.]\\s*\\d)")
        contextual.findAll(text).forEach { match ->
            numericMatches += Triple(match.groups[1]!!.range.first, match.groupValues[1].toIntOrNull() ?: return@forEach, 0)
        }
        val suffixed = Regex("(?<!\\d)(\\d{1,2})\\s*['’](?:ye|ya|e|a|de|da|te|ta)\\b")
        suffixed.findAll(text).forEach { match ->
            numericMatches += Triple(match.groups[1]!!.range.first, match.groupValues[1].toIntOrNull() ?: return@forEach, 0)
        }
        val numeric = numericMatches.distinctBy { it.first }.sortedBy { it.first }.mapNotNull { (_, hourValue, minuteValue) ->
            val hour = hourValue
            val minute = minuteValue
            if (hour in 0..23 && minute in 0..59) hour to minute else null
        }.toList()
        if (numeric.isNotEmpty()) return numeric
        val tokens = tokenPattern.findAll(text).map { it.value }.toList()
        return tokens.indices.mapNotNull { index ->
            if (tokens[index] !in setOf("saat", "sabah", "akşam", "gece", "öğleden")) return@mapNotNull null
            val numberIndex = if (tokens.getOrNull(index + 1) == "saat") index + 2 else index + 1
            val (hour, consumed) = spokenNumberAt(tokens, numberIndex) ?: return@mapNotNull null
            if (hour !in 0..23) return@mapNotNull null
            val minute = spokenNumberAt(tokens, numberIndex + consumed)?.first?.takeIf { it in 1..59 } ?: 0
            resolveAlarmHour(hour, text) to minute
        }
    }

    fun parseCorrection(previousTranscript: String, correctionTranscript: String): NativeAlarmCommand? {
        val previous = parse(previousTranscript) as? NativeAlarmCommand.Create ?: return null
        val correction = normalize(correctionTranscript)
        if (listOf("hayır", "hayir", "değil", "degil", "olsun", "yerine").none(correction::contains)) return null
        if ("yarın" in correction || "yarin" in correction) return previous.copy(tomorrow = true)
        if ("bugün" in correction || "bugun" in correction) return previous.copy(tomorrow = false)
        val changed = findNumericTime(correction) ?: findSpokenTime(correction) ?: run {
            val tokens = tokenPattern.findAll(correction).map { it.value }.toList()
            val numberIndex = tokens.indices.lastOrNull { spokenNumberAt(tokens, it) != null } ?: return null
            val (number, _) = spokenNumberAt(tokens, numberIndex) ?: return null
            number to 0
        }
        val correctedHour = changed.first
        if (correctedHour !in 0..23 || changed.second !in 0..59) return null
        val isPeriodSpecified = "sabah" in correction || "öğleden önce" in correction ||
            "akşam" in correction || "öğleden sonra" in correction || "gece" in correction
        val hour = when {
            correctedHour > 12 || correctedHour == 0 || isPeriodSpecified -> correctedHour
            previous.hour >= 12 -> correctedHour + 12
            else -> correctedHour
        }
        val minuteWasSpecified = hourPattern.find(correction)?.groupValues?.get(2)?.isNotEmpty() == true
        val minute = if (minuteWasSpecified) changed.second else 0
        return previous.copy(hour = hour, minute = minute, tomorrow = false)
    }

    private fun relativeDurationMinutes(text: String): Int? {
        val tokens = tokenPattern.findAll(text).map { it.value }.toList()
        for (unitIndex in tokens.indices) {
            val unit = tokens[unitIndex]
            if (unit !in setOf("dakika", "dk", "saat")) continue
            if (tokens.getOrNull(unitIndex + 1) !in setOf("sonra", "sonrasına", "sonrasina")) continue
            val duration = when {
                unit == "saat" && tokens.getOrNull(unitIndex - 1) == "yarım" -> 30
                unit == "saat" && tokens.getOrNull(unitIndex - 1) == "buçuk" -> {
                    val base = spokenNumberAt(tokens, unitIndex - 2)?.first ?: return null
                    base * 60 + 30
                }
                unit == "saat" -> (spokenNumberAt(tokens, unitIndex - 1)?.first ?: return null) * 60
                else -> spokenNumberAt(tokens, unitIndex - 1)?.first ?: return null
            }
            if (duration !in 1..(24 * 60)) return null
            return duration
        }
        return null
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
        val token = tokens.getOrNull(index) ?: return null
        token.toIntOrNull()?.let { return it to 1 }
        val first = numberWords[token] ?: return null
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
                    val days: List<Int> = emptyList(), val skipUi: Boolean = false, val alarmSearchMode: String? = null)

    fun planFor(command: NativeAlarmCommand): Plan? = when (command) {
        is NativeAlarmCommand.Create -> Plan(AlarmClock.ACTION_SET_ALARM, command.hour, command.minute, command.days, true)
        NativeAlarmCommand.Show -> Plan(AlarmClock.ACTION_SHOW_ALARMS)
        NativeAlarmCommand.Snooze -> Plan(AlarmClock.ACTION_SNOOZE_ALARM)
        NativeAlarmCommand.Dismiss -> Plan(AlarmClock.ACTION_DISMISS_ALARM, alarmSearchMode = AlarmClock.ALARM_SEARCH_MODE_NEXT)
        is NativeAlarmCommand.ListAlarms,
        NativeAlarmCommand.EnableAll,
        is NativeAlarmCommand.EnableOne,
        is NativeAlarmCommand.DeleteAll,
        is NativeAlarmCommand.RelativeCreate,
        NativeAlarmCommand.DisableAll,
        is NativeAlarmCommand.DisableOne,
        is NativeAlarmCommand.DeleteOne,
        is NativeAlarmCommand.EditOne,
        NativeAlarmCommand.Manage,
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
            plan.alarmSearchMode?.let { putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, it) }
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
    private val diagnostics: NativeAlarmDiagnostics = AndroidNativeAlarmDiagnostics,
    private val management: AlarmManagementGateway? = null
) {
    constructor(context: Context) : this(
        AndroidNativeAlarmLauncher(context.applicationContext),
        management = AccessibilityAlarmManagementGateway()
    )

    fun process(
        transcript: String,
        interactionId: String = "unknown",
        correctionContext: String? = null
    ): LocalActionResult? {
        val command = TurkishAlarmCommandParser.parse(transcript)
            ?: correctionContext?.let { TurkishAlarmCommandParser.parseCorrection(it, transcript) }
            ?: return null
        val safeTranscript = LocalCommandDiagnostics.safeTranscript(transcript)
        val normalized = LocalCommandDiagnostics.safeTranscript(TurkishAlarmCommandParser.normalize(transcript))
        diagnostics.info("local_command_candidate interactionId=$interactionId transcript=\"$safeTranscript\" normalized=\"$normalized\"")
        diagnostics.info("local_command_selected interactionId=$interactionId intent=${command.javaClass.simpleName}")
        when (command) {
            NativeAlarmCommand.DisableAll, NativeAlarmCommand.EnableAll,
            is NativeAlarmCommand.DisableOne, is NativeAlarmCommand.EnableOne,
            is NativeAlarmCommand.DeleteAll, is NativeAlarmCommand.DeleteOne,
            is NativeAlarmCommand.EditOne, is NativeAlarmCommand.ListAlarms -> {
                return management?.execute(command, interactionId) ?: unsupportedManagement(
                    interactionId,
                    command.javaClass.simpleName,
                    "Gerçek alarm yönetimi için Ayarlar'dan Ayşe Alarm Erişimi hizmetini etkinleştirmen gerekiyor."
                )
            }
            NativeAlarmCommand.Manage -> return unsupportedManagement(interactionId, "manage", "Alarm isteğini anlayamadım.")
            else -> Unit
        }
        if (command is NativeAlarmCommand.Clarify) {
            diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm result=clarification")
            return LocalActionResult(LocalActionStatus.NEEDS_CLARIFICATION, command.message)
        }
        val isRelativeRequest = command is NativeAlarmCommand.RelativeCreate
        val effectiveCommand = if (command is NativeAlarmCommand.RelativeCreate) {
            val current = nowCalendar()
            val target = RelativeAlarmCalculator.calculate(current.timeInMillis, command.durationMinutes, current.timeZone)
                ?: return LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Alarm zamanı hesaplanamadı.")
            diagnostics.info("relative_alarm_calculated interactionId=$interactionId duration_minutes=${command.durationMinutes} target_epoch_ms=${target.epochMillis} local_target=${target.localDateTime}")
            NativeAlarmCommand.Create(target.hour, target.minute, emptyList(), tomorrow = false)
        } else command

        if (effectiveCommand is NativeAlarmCommand.Create && effectiveCommand.tomorrow && effectiveCommand.days.isEmpty()) {
            val now = nowCalendar()
            val target = nowCalendar().apply {
                timeInMillis = now.timeInMillis
                set(Calendar.HOUR_OF_DAY, effectiveCommand.hour)
                set(Calendar.MINUTE, effectiveCommand.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.after(now)) {
                diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm result=tomorrow_requires_clock_confirmation")
                return LocalActionResult(LocalActionStatus.NEEDS_CLARIFICATION,
                    "Saat uygulaması yarın için tarihi doğrudan seçemiyor. Yanlışlıkla bugüne kurmamak için Saat uygulamasında yarını seçmelisin.")
            }
        }
        if (effectiveCommand is NativeAlarmCommand.Create) {
            diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm action=create hour=${effectiveCommand.hour} minute=${effectiveCommand.minute} days=${effectiveCommand.days.size} tomorrow=${effectiveCommand.tomorrow}")
        } else {
            diagnostics.info("local_handler_called interactionId=$interactionId handler=alarm action=${effectiveCommand.javaClass.simpleName}")
        }
        val plan = NativeAlarmIntents.planFor(effectiveCommand)
            ?: return LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Alarm isteği hazırlanamadı.")
        val target = try {
            launcher.resolve(plan)
        } catch (error: RuntimeException) {
            diagnostics.warning("alarm_intent_resolution_failed interactionId=$interactionId action=${plan.action} type=${error.javaClass.simpleName}")
            return LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Saat uygulaması alarm isteğini denetleyemedi.")
        }
        if (target == null) {
            diagnostics.warning("alarm_intent_unhandled interactionId=$interactionId action=${plan.action}")
            return LocalActionResult(LocalActionStatus.UNSUPPORTED, "Bu telefonda bu alarm işlemini destekleyen bir Saat uygulaması bulamadım.")
        }
        diagnostics.info("alarm_intent_constructed interactionId=$interactionId action=${plan.action} target=$target hour=${commandHour(effectiveCommand)} minute=${commandMinute(effectiveCommand)}")
        if (effectiveCommand == NativeAlarmCommand.Show) {
            return LocalActionResult(LocalActionStatus.ACTION_DISPATCHED,
                "Saat uygulamasını açıyorum. Alarmları uygulama içinde görüntüleyebilirsin.",
                afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
        }
        return when (effectiveCommand) {
            is NativeAlarmCommand.Create -> {
                val displayTime = String.format(Locale("tr", "TR"), "%02d.%02d", effectiveCommand.hour, effectiveCommand.minute)
                LocalActionResult(LocalActionStatus.ACTION_DISPATCHED,
                    "Saat uygulamasına ${if (isRelativeRequest) "yaklaşık " else ""}$displayTime için alarm kurma isteğini gönderiyorum. Eklendiğini uygulamada doğrula.",
                    afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
            }
            NativeAlarmCommand.Snooze -> LocalActionResult(LocalActionStatus.ACTION_DISPATCHED,
                "Saat uygulamasına erteleme isteğini iletiyorum; çalan alarmın ertelendiğini buradan doğrulayamıyorum.",
                afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
            NativeAlarmCommand.Dismiss -> LocalActionResult(LocalActionStatus.ACTION_DISPATCHED,
                "Saat uygulamasına çalan alarmı susturma isteğini gönderiyorum; sonucu uygulamanın kendisi belirliyor.",
                afterSpeech = { launchAfterSpeech(plan, interactionId, target) })
            else -> LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Alarm isteği hazırlanamadı.")
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

    private fun unsupportedManagement(interactionId: String, operation: String, message: String): LocalActionResult {
        diagnostics.warning("alarm_management_unavailable interactionId=$interactionId operation=$operation result_unverified=true")
        return LocalActionResult(LocalActionStatus.UNSUPPORTED, message)
    }
}

internal data class RelativeAlarmTarget(
    val epochMillis: Long,
    val localDateTime: String,
    val hour: Int,
    val minute: Int
)

internal object RelativeAlarmCalculator {
    fun calculate(nowMillis: Long, durationMinutes: Int, timeZone: java.util.TimeZone): RelativeAlarmTarget? {
        if (durationMinutes !in 1..(24 * 60)) return null
        val exactTargetMillis = nowMillis + durationMinutes * 60_000L
        // AlarmClock accepts hour/minute only. Round up so second precision never makes it ring early.
        val targetMillis = ((exactTargetMillis + 59_999L) / 60_000L) * 60_000L
        val target = Calendar.getInstance(timeZone, Locale("tr", "TR")).apply { timeInMillis = targetMillis }
        val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("tr", "TR")).apply {
            this.timeZone = timeZone
        }.format(target.time)
        return RelativeAlarmTarget(
            epochMillis = targetMillis,
            localDateTime = dateTime,
            hour = target.get(Calendar.HOUR_OF_DAY),
            minute = target.get(Calendar.MINUTE)
        )
    }
}
