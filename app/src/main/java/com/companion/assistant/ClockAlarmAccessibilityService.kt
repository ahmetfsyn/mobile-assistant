package com.companion.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/**
 * User-enabled semantic integration for the verified Google Clock package on the GM 5 Plus.
 * It only uses resource IDs and accessibility node actions; it never taps screen coordinates.
 */
internal class ClockAlarmAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var request: PendingRequest? = null
    private val poll = Runnable { advanceRequest() }
    private val timeout = Runnable { finish(LocalActionStatus.LOCAL_FAILURE, "Saat uygulaması zamanında yanıt vermedi; işlemi doğrulayamadım.") }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        val info = serviceInfo
        info.packageNames = arrayOf(CLOCK_PACKAGE)
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 120
        info.flags = info.flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        Log.i(TAG, "clock_accessibility_connected package=$CLOCK_PACKAGE")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (request == null || event?.packageName?.toString() != CLOCK_PACKAGE) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) schedulePoll(180)
    }

    override fun onInterrupt() {
        if (request != null) finish(LocalActionStatus.LOCAL_FAILURE, "Alarm işlemi kesildi; değişiklik doğrulanmadı.")
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (request != null) finish(LocalActionStatus.LOCAL_FAILURE, "Alarm erişim hizmeti kapandı; işlem doğrulanmadı.")
        clearPending()
        super.onDestroy()
    }

    internal fun executeAlarmCommand(command: NativeAlarmCommand, interactionId: String, callback: (LocalActionResult) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { executeAlarmCommand(command, interactionId, callback) }
            return
        }
        if (request != null) {
            callback(LocalActionResult(LocalActionStatus.LOCAL_FAILURE, "Başka bir alarm işlemi sürüyor."))
            return
        }
        val installedPackage = try {
            packageManager.getPackageInfo(CLOCK_PACKAGE, 0)
        } catch (_: Exception) {
            callback(LocalActionResult(LocalActionStatus.UNSUPPORTED, "Desteklenen Google Saat uygulaması bulunamadı."))
            return
        }
        val version = installedPackage.versionName.orEmpty()
        val pending = PendingRequest(command, interactionId, version, callback)
        request = pending
        mainHandler.postDelayed(timeout, REQUEST_TIMEOUT_MS)
        try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .setPackage(CLOCK_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.i(TAG, "clock_management_started interactionId=$interactionId version=$version command=${command.javaClass.simpleName}")
            schedulePoll(450)
        } catch (error: Exception) {
            finish(LocalActionStatus.UNSUPPORTED, "Saat uygulamasını açamadım: ${error.javaClass.simpleName}.")
        }
    }

    private fun advanceRequest() {
        val pending = request ?: return
        if (SystemClock.elapsedRealtime() - pending.startedAt > REQUEST_TIMEOUT_MS) {
            finish(LocalActionStatus.LOCAL_FAILURE, "Saat uygulamasında işlem zaman aşımına uğradı; sonucu doğrulayamadım.")
            return
        }
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != CLOCK_PACKAGE) {
            schedulePoll(POLL_INTERVAL_MS)
            return
        }
        when (pending.stage) {
            Stage.WAIT_LIST -> prepareFromList(pending, root)
            Stage.SET_VERIFY -> verifyEnable(pending, root)
            Stage.DELETE_EDITOR -> deleteFromEditor(pending, root)
            Stage.DELETE_CONFIRM -> confirmOrVerifyDelete(pending, root)
            Stage.DELETE_VERIFY -> verifyDelete(pending, root)
            Stage.EDIT_EDITOR -> editFromEditor(pending, root)
            Stage.EDIT_VERIFY -> verifyEdit(pending, root)
        }
    }

    private fun prepareFromList(pending: PendingRequest, root: AccessibilityNodeInfo) {
        if (nodesById(root, "alarm_recycler_view").isEmpty()) {
            if (++pending.attempts >= VERIFY_ATTEMPTS) {
                finish(LocalActionStatus.UNSUPPORTED, "Google Saat alarm listesi tanınmadı; hiçbir alarm değişikliği yapmadım.")
            } else schedulePoll(POLL_INTERVAL_MS)
            return
        }
        val rows = readAlarmRows(root)
        if (!pending.listRead) {
            pending.listRead = true
            when (val command = pending.command) {
                NativeAlarmCommand.Show, is NativeAlarmCommand.ListAlarms -> {
                    val enabledOnly = command is NativeAlarmCommand.ListAlarms && command.enabledOnly
                    val message = AlarmManagementPolicy.listSummary(rows.map { it.entry }, enabledOnly)
                    finish(LocalActionStatus.LOCAL_SUCCESS, message)
                    return
                }
                else -> Unit
            }
            val targets = when (val command = pending.command) {
                NativeAlarmCommand.EnableAll -> rows.filterNot { it.entry.enabled }.map { it.entry }
                NativeAlarmCommand.DisableAll -> rows.filter { it.entry.enabled }.map { it.entry }
                is NativeAlarmCommand.EnableOne -> selectOne(rows.map { it.entry }, command.target, "etkinleştirmek") ?: return
                is NativeAlarmCommand.DisableOne -> selectOne(rows.map { it.entry }, command.target, "kapatmak") ?: return
                is NativeAlarmCommand.DeleteOne -> selectOne(rows.map { it.entry }, command.target, "silmek") ?: return
                is NativeAlarmCommand.DeleteAll -> rows.map { it.entry }.filter { !command.enabledOnly || it.enabled }
                is NativeAlarmCommand.EditOne -> selectOne(rows.map { it.entry }, command.target, "düzenlemek") ?: return
                else -> {
                    finish(LocalActionStatus.UNSUPPORTED, "Bu alarm işlemi desteklenmiyor.")
                    return
                }
            }
            pending.targets = ArrayDeque(targets)
            when (pending.command) {
                is NativeAlarmCommand.DeleteOne, is NativeAlarmCommand.DeleteAll -> processNextDelete(pending, root)
                is NativeAlarmCommand.EditOne -> beginEdit(pending, root)
                else -> processNextToggle(pending, root)
            }
            return
        }
        schedulePoll(POLL_INTERVAL_MS)
    }

    private fun selectOne(alarms: List<AlarmEntry>, target: AlarmTarget?, action: String): List<AlarmEntry>? {
        if (target == null) {
            finish(LocalActionStatus.NEEDS_CLARIFICATION, "Hangi alarmı $action istediğini saat veya etiketle belirtir misin?")
            return null
        }
        return when (val match = AlarmManagementPolicy.select(alarms, target)) {
            AlarmMatch.None -> {
                finish(LocalActionStatus.LOCAL_FAILURE, "Belirttiğin alarmı bulamadım; hiçbir değişiklik yapmadım.")
                null
            }
            is AlarmMatch.Many -> {
                val choices = match.alarms.joinToString(" veya ") { row -> "${row.displayTime}${row.label?.let { " $it" }.orEmpty()}" }
                finish(LocalActionStatus.NEEDS_CLARIFICATION, "Birden fazla alarm eşleşti: $choices. Hangisini $action istediğini belirtir misin?")
                null
            }
            is AlarmMatch.One -> listOf(match.alarm)
        }
    }

    private fun processNextToggle(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val desired = when (pending.command) {
            NativeAlarmCommand.EnableAll, is NativeAlarmCommand.EnableOne -> true
            else -> false
        }
        val currentRows = readAlarmRows(root)
        val chosen = pending.targets.firstOrNull { candidate ->
            currentRows.any { it.entry.signature == candidate.signature && it.entry.enabled != desired }
        }
        if (chosen == null) {
            if (pending.successCount == 0 && pending.failureCount == 0) {
                val single = pending.command is NativeAlarmCommand.EnableOne || pending.command is NativeAlarmCommand.DisableOne
                finish(LocalActionStatus.LOCAL_SUCCESS, if (single) "Alarm zaten ${if (desired) "açık" else "kapalı"}." else "Tüm alarmlar zaten ${if (desired) "açık" else "kapalı"}.")
            } else {
                val total = pending.successCount + pending.failureCount
                val message = if (pending.failureCount == 0) "${pending.successCount} alarm doğrulanarak ${if (desired) "etkinleştirildi" else "kapatıldı"}."
                else "${pending.successCount} alarm doğrulandı, ${pending.failureCount} alarm değiştirilemedi."
                finish(AlarmManagementPolicy.batchStatus(pending.successCount, pending.failureCount), message)
            }
            return
        }
        val row = currentRows.firstOrNull { it.entry.signature == chosen.signature && it.entry.enabled != desired }
        if (row == null || !safeClick(row.switchNode)) {
            pending.targets.removeFirstOccurrence(chosen)
            pending.failureCount++
            processNextToggle(pending, root)
            return
        }
        pending.pendingTarget = chosen
        pending.pendingDesired = desired
        pending.stage = Stage.SET_VERIFY
        pending.attempts = 0
        schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun verifyEnable(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val target = pending.pendingTarget ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Alarm doğrulanamadı.")
        val actual = readAlarmRows(root).firstOrNull { it.entry.signature == target.signature }
        if (AlarmManagementPolicy.enabledStateVerified(actual?.entry, pending.pendingDesired)) {
            pending.successCount++
            pending.targets.removeFirstOccurrence(target)
            pending.stage = Stage.WAIT_LIST
            pending.attempts = 0
            pending.pendingTarget = null
            processNextToggle(pending, root)
        } else if (++pending.attempts >= VERIFY_ATTEMPTS) {
            pending.failureCount++
            pending.targets.removeFirstOccurrence(target)
            pending.stage = Stage.WAIT_LIST
            pending.pendingTarget = null
            processNextToggle(pending, root)
        } else schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun processNextDelete(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val target = pending.targets.firstOrNull()
        if (target == null) {
            val message = when {
                pending.successCount > 0 && pending.failureCount == 0 -> "${pending.successCount} alarm silindi ve listeden kaldırıldığı doğrulandı."
                pending.successCount > 0 -> "${pending.successCount} alarm silindi; ${pending.failureCount} alarm silinemedi veya doğrulanamadı."
                pending.failureCount > 0 -> "Hiçbir alarmı doğrulayamadım; ${pending.failureCount} işlem başarısız oldu."
                else -> "Silinecek alarm bulamadım."
            }
            finish(AlarmManagementPolicy.batchStatus(pending.successCount, pending.failureCount), message)
            return
        }
        val rows = readAlarmRows(root)
        val row = rows.firstOrNull { it.entry.signature == target.signature }
        if (row == null) {
            pending.targets.removeFirst()
            pending.failureCount++
            processNextDelete(pending, root)
            return
        }
        pending.pendingTarget = target
        pending.deleteSnapshot = rows.map { it.entry }
        pending.stage = Stage.DELETE_EDITOR
        pending.attempts = 0
        if (!safeClick(row.cardNode)) {
            pending.failureCount++
            pending.targets.removeFirst()
            pending.stage = Stage.WAIT_LIST
            processNextDelete(pending, root)
        } else schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun deleteFromEditor(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val delete = nodesById(root, "delete_button").firstOrNull()
        if (delete == null) {
            if (++pending.attempts >= VERIFY_ATTEMPTS) {
                pending.failureCount++
                pending.targets.pollFirst()
                pending.stage = Stage.WAIT_LIST
                processNextDelete(pending, root)
            } else schedulePoll(VERIFY_INTERVAL_MS)
            return
        }
        pending.stage = Stage.DELETE_CONFIRM
        pending.attempts = 0
        if (!safeClick(delete)) {
            pending.failureCount++
            pending.targets.pollFirst()
            pending.stage = Stage.WAIT_LIST
            schedulePoll(POLL_INTERVAL_MS)
        } else schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun confirmOrVerifyDelete(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val target = pending.pendingTarget ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Silme hedefi kayboldu.")
        val rows = readAlarmRows(root)
        if (isAlarmList(root) && AlarmManagementPolicy.deletionVerified(pending.deleteSnapshot, rows.map { it.entry }, target)) {
            pending.successCount++
            pending.targets.pollFirst()
            pending.pendingTarget = null
            pending.stage = Stage.WAIT_LIST
            processNextDelete(pending, root)
            return
        }
        if (!pending.confirmationClicked) {
            val confirm = findButtonByText(root, setOf("sil", "silinsin", "delete", "remove"))
            if (confirm != null) {
                pending.confirmationClicked = true
                if (!safeClick(confirm, allowClickableParent = true)) pending.failureCount++
            }
        }
        pending.stage = Stage.DELETE_VERIFY
        pending.attempts = 0
        schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun verifyDelete(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val target = pending.pendingTarget ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Silme hedefi kayboldu.")
        val rows = readAlarmRows(root)
        if (isAlarmList(root) && AlarmManagementPolicy.deletionVerified(pending.deleteSnapshot, rows.map { it.entry }, target)) {
            pending.successCount++
            pending.targets.pollFirst()
            pending.pendingTarget = null
            pending.confirmationClicked = false
            pending.stage = Stage.WAIT_LIST
            processNextDelete(pending, root)
        } else if (++pending.attempts >= VERIFY_ATTEMPTS) {
            pending.failureCount++
            pending.targets.pollFirst()
            pending.pendingTarget = null
            pending.confirmationClicked = false
            pending.stage = Stage.WAIT_LIST
            processNextDelete(pending, root)
        } else schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun beginEdit(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val target = pending.targets.firstOrNull() ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Düzenlenecek alarm seçilemedi.")
        val currentRows = readAlarmRows(root)
        pending.deleteSnapshot = currentRows.map { it.entry }
        val row = currentRows.firstOrNull { it.entry.signature == target.signature }
            ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Düzenlenecek alarm artık listede yok.")
        pending.pendingTarget = target
        pending.stage = Stage.EDIT_EDITOR
        pending.attempts = 0
        if (!safeClick(row.cardNode)) finish(LocalActionStatus.LOCAL_FAILURE, "Alarm düzenleme ekranı açılamadı.")
        else schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun editFromEditor(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val command = pending.command as? NativeAlarmCommand.EditOne ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Düzenleme isteği geçersiz.")
        val hourField = nodesById(root, "hour_input").firstOrNull()
        val minuteField = nodesById(root, "minute_input").firstOrNull()
        val save = nodesById(root, "save_button").firstOrNull()
        if (hourField == null || minuteField == null || save == null) {
            if (++pending.attempts >= VERIFY_ATTEMPTS) finish(LocalActionStatus.UNSUPPORTED, "Saat uygulamasının düzenleme alanları erişilebilir değil; alarm değiştirilmedi.")
            else schedulePoll(VERIFY_INTERVAL_MS)
            return
        }
        val hourSet = setText(hourField, String.format(Locale.US, "%02d", command.newHour))
        val minuteSet = setText(minuteField, String.format(Locale.US, "%02d", command.newMinute))
        if (!hourSet || !minuteSet || !safeClick(save)) {
            finish(LocalActionStatus.LOCAL_FAILURE, "Saat uygulaması yeni alarm saatini kabul etmedi; sonucu doğrulayamıyorum.")
            return
        }
        pending.stage = Stage.EDIT_VERIFY
        pending.attempts = 0
        schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun verifyEdit(pending: PendingRequest, root: AccessibilityNodeInfo) {
        val command = pending.command as? NativeAlarmCommand.EditOne ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Düzenleme isteği geçersiz.")
        val old = pending.pendingTarget ?: return finish(LocalActionStatus.LOCAL_FAILURE, "Düzenleme hedefi kayboldu.")
        val rows = readAlarmRows(root).map { it.entry }
        val newTime = command.newHour * 60 + command.newMinute
        if (AlarmManagementPolicy.editVerified(pending.deleteSnapshot, rows, old, newTime)) {
            finish(LocalActionStatus.LOCAL_SUCCESS, "Alarm ${formatTime(newTime)} olarak değiştirildi ve listede doğrulandı.")
        } else if (++pending.attempts >= VERIFY_ATTEMPTS) {
            finish(LocalActionStatus.LOCAL_FAILURE, "Yeni alarm saatini listede doğrulayamadım; değişiklik yapıldığını söyleyemem.")
        } else schedulePoll(VERIFY_INTERVAL_MS)
    }

    private fun readAlarmRows(root: AccessibilityNodeInfo): List<AlarmRow> =
        nodesById(root, "alarm_card").mapNotNull { card ->
            val timeNode = nodesById(card, "digital_clock").firstOrNull()
            val time = parseTime(timeNode?.text?.toString().orEmpty().ifBlank { timeNode?.contentDescription?.toString().orEmpty() })
                ?: return@mapNotNull null
            val switch = nodesById(card, "onoff").firstOrNull()?.takeIf { it.isCheckable } ?: return@mapNotNull null
            val texts = descendants(card).mapNotNull { node ->
                node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
                    ?: node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
            }.distinct()
            val label = texts.firstOrNull { value ->
                value != timeNode?.text?.toString() && parseTime(value) == null && value.length <= 60 &&
                    value.lowercase(Locale("tr", "TR")).let { lowered ->
                        "alarm" !in lowered && lowered !in setOf("düzenle", "edit", "tekrarla", "repeat", "titreşim", "vibrate")
                    }
            }
            val display = formatTime(time)
            AlarmRow(AlarmEntry(time, display, label, switch.isChecked), card, switch)
        }

    private fun nodesById(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> = try {
        root.findAccessibilityNodeInfosByViewId("$CLOCK_PACKAGE:id/$id") ?: emptyList()
    } catch (_: RuntimeException) { emptyList() }

    private fun isAlarmList(root: AccessibilityNodeInfo): Boolean = nodesById(root, "alarm_recycler_view").isNotEmpty()

    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_NODES) {
            val node = stack.removeLast()
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(stack::add)
        }
        return result
    }

    private fun findButtonByText(root: AccessibilityNodeInfo, candidates: Set<String>): AccessibilityNodeInfo? =
        descendants(root).firstOrNull { node ->
            val text = node.text?.toString()?.trim()?.lowercase(Locale("tr", "TR"))
                ?: node.contentDescription?.toString()?.trim()?.lowercase(Locale("tr", "TR"))
            text != null && text in candidates
        }

    private fun safeClick(node: AccessibilityNodeInfo, allowClickableParent: Boolean = false): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!allowClickableParent) return false
        var target: AccessibilityNodeInfo? = node
        repeat(5) {
            if (target == null) return false
            if (target!!.isClickable) return target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target!!.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean = try {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } catch (_: RuntimeException) { false }

    private fun parseTime(text: String): Int? {
        val match = Regex("(?<!\\d)(\\d{1,2})[:.](\\d{2})(?:\\s*(ÖÖ|ÖS|AM|PM))?", RegexOption.IGNORE_CASE).find(text) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val period = match.groupValues[3].uppercase(Locale("tr", "TR"))
        if (hour !in 0..23 || minute !in 0..59) return null
        if (period in setOf("ÖS", "PM") && hour in 1..11) hour += 12
        if (period in setOf("ÖÖ", "AM") && hour == 12) hour = 0
        return hour * 60 + minute
    }

    private fun formatTime(minutes: Int): String = String.format(Locale("tr", "TR"), "%02d.%02d", minutes / 60, minutes % 60)

    private fun schedulePoll(delay: Long) {
        mainHandler.removeCallbacks(poll)
        mainHandler.postDelayed(poll, delay)
    }

    private fun finish(status: LocalActionStatus, message: String) {
        val active = request ?: return
        Log.i(TAG, "clock_management_finished interactionId=${active.interactionId} status=${status.name.lowercase(Locale.US)} verified=${status == LocalActionStatus.LOCAL_SUCCESS}")
        clearPending()
        returnToCompanion()
        active.callback(LocalActionResult(status, message))
    }

    private fun returnToCompanion() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ))
        } catch (error: Exception) {
            Log.w(TAG, "return_to_companion_failed type=${error.javaClass.simpleName}")
        }
    }

    private fun clearPending() {
        mainHandler.removeCallbacks(poll)
        mainHandler.removeCallbacks(timeout)
        request = null
    }

    private fun cancelRequest(interactionId: String) {
        if (request?.interactionId == interactionId) {
            Log.w(TAG, "clock_management_cancelled interactionId=$interactionId")
            clearPending()
        }
    }

    private data class AlarmRow(val entry: AlarmEntry, val cardNode: AccessibilityNodeInfo, val switchNode: AccessibilityNodeInfo)

    private enum class Stage { WAIT_LIST, SET_VERIFY, DELETE_EDITOR, DELETE_CONFIRM, DELETE_VERIFY, EDIT_EDITOR, EDIT_VERIFY }

    private class PendingRequest(
        val command: NativeAlarmCommand,
        val interactionId: String,
        val clockVersion: String,
        val callback: (LocalActionResult) -> Unit
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var stage = Stage.WAIT_LIST
        var listRead = false
        var targets: ArrayDeque<AlarmEntry> = ArrayDeque()
        var pendingTarget: AlarmEntry? = null
        var pendingDesired = false
        var deleteSnapshot: List<AlarmEntry> = emptyList()
        var successCount = 0
        var failureCount = 0
        var attempts = 0
        var confirmationClicked = false
    }

    companion object {
        const val TAG = "ClockAlarmAccess"
        const val CLOCK_PACKAGE = "com.google.android.deskclock"
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 350L
        const val VERIFY_INTERVAL_MS = 250L
        const val VERIFY_ATTEMPTS = 12
        const val MAX_NODES = 250
        @Volatile internal var current: ClockAlarmAccessibilityService? = null
        internal val isManaging: Boolean get() = current?.request != null

        internal fun cancelCommand(interactionId: String) {
            current?.let { service -> service.mainHandler.post { service.cancelRequest(interactionId) } }
        }
    }
}
