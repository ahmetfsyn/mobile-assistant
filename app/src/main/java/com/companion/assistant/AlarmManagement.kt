package com.companion.assistant

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class AlarmEntry(
    val timeMinutes: Int,
    val displayTime: String,
    val label: String?,
    val enabled: Boolean
) {
    val signature: String get() = "$timeMinutes|${label.orEmpty().lowercase(java.util.Locale("tr", "TR"))}"
}

internal sealed class AlarmMatch {
    data class One(val alarm: AlarmEntry) : AlarmMatch()
    data class Many(val alarms: List<AlarmEntry>) : AlarmMatch()
    object None : AlarmMatch()
}

/** Pure matching rules shared by the accessibility controller and unit tests. */
internal object AlarmManagementPolicy {
    fun select(alarms: List<AlarmEntry>, target: AlarmTarget): AlarmMatch {
        val matches = alarms.filter { alarm ->
            (target.timeMinutes == null || if (target.timeIsAmbiguous) {
                alarm.timeMinutes % (12 * 60) == target.timeMinutes % (12 * 60)
            } else alarm.timeMinutes == target.timeMinutes) &&
                (target.label == null || alarm.label?.contains(target.label, ignoreCase = true) == true)
        }
        return when (matches.size) {
            0 -> AlarmMatch.None
            1 -> AlarmMatch.One(matches.single())
            else -> AlarmMatch.Many(matches)
        }
    }

    fun unverifiedCount(before: List<AlarmEntry>, after: List<AlarmEntry>, target: AlarmEntry): Boolean =
        before.count { it.signature == target.signature } > after.count { it.signature == target.signature }

    fun enabledStateVerified(actual: AlarmEntry?, desired: Boolean): Boolean = actual?.enabled == desired

    fun batchStatus(successCount: Int, failureCount: Int): LocalActionStatus =
        if (failureCount == 0) LocalActionStatus.LOCAL_SUCCESS else LocalActionStatus.LOCAL_FAILURE

    fun deletionVerified(before: List<AlarmEntry>, after: List<AlarmEntry>, target: AlarmEntry): Boolean =
        unverifiedCount(before, after, target)

    fun editVerified(before: List<AlarmEntry>, after: List<AlarmEntry>, target: AlarmEntry, newTimeMinutes: Int): Boolean =
        before.count { it.signature == target.signature } > after.count { it.signature == target.signature } &&
            after.any { it.timeMinutes == newTimeMinutes && (target.label == null || it.label?.contains(target.label, true) == true) }

    fun listSummary(alarms: List<AlarmEntry>, enabledOnly: Boolean): String {
        val shown = if (enabledOnly) alarms.filter { it.enabled } else alarms
        if (shown.isEmpty()) return "Kayıtlı ${if (enabledOnly) "etkin " else ""}alarm bulamadım."
        val prefix = if (enabledOnly) "Etkin alarmlar" else "Alarmlar"
        return "$prefix: " + shown.joinToString(", ") { alarm ->
            "${alarm.displayTime}${alarm.label?.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()} ${if (alarm.enabled) "açık" else "kapalı"}"
        } + "."
    }
}

internal interface AlarmManagementGateway {
    fun execute(command: NativeAlarmCommand, interactionId: String): LocalActionResult
}

/** Bridges the voice worker to the explicitly user-enabled Clock accessibility service. */
internal class AccessibilityAlarmManagementGateway : AlarmManagementGateway {
    override fun execute(command: NativeAlarmCommand, interactionId: String): LocalActionResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<LocalActionResult>()
        val service = ClockAlarmAccessibilityService.current
            ?: return LocalActionResult(
                LocalActionStatus.UNSUPPORTED,
                "Gerçek alarm yönetimi için Ayarlar'dan Ayşe Alarm Erişimi hizmetini etkinleştirmen gerekiyor."
            )
        val request = Runnable {
            service.executeAlarmCommand(command, interactionId) {
                result.set(it)
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post(request)
        } else {
            Handler(Looper.getMainLooper()).post(request)
        }
        return try {
            if (latch.await(35, TimeUnit.SECONDS)) {
                result.get() ?: LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Alarm işlemi sonuç vermedi.")
            } else {
                LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Saat uygulamasından doğrulanmış yanıt alınamadı.")
            }
        } catch (_: InterruptedException) {
            ClockAlarmAccessibilityService.cancelCommand(interactionId)
            Thread.currentThread().interrupt()
            LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Alarm işlemi kesildi.")
        }
    }
}
