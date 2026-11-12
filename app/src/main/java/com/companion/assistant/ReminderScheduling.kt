package com.companion.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.TimeZone

internal class ReminderScheduler(context: Context, private val store: ReminderStore) {
    private val appContext = context.applicationContext
    private val settingsStore = ReminderSettingsStore(appContext)
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleAll() {
        store.resetInFlight()
        store.list().forEach(::cancel)
        if (!settingsStore.get().autonomyEnabled) return
        store.list(includeCompleted = false)
            .filter { it.status == STATUS_SCHEDULED }
            .forEach(::schedule)
    }

    fun schedule(reminder: ReminderRecord) {
        if (!settingsStore.get().autonomyEnabled || reminder.status != STATUS_SCHEDULED) {
            cancel(reminder)
            return
        }
        val expectedAt = reminder.pendingAtMillis ?: reminder.nextAtMillis
        val triggerAt = reminder.nextAtMillis.coerceAtLeast(System.currentTimeMillis() + MIN_ALARM_DELAY_MS)
        setAlarm(reminder.id, expectedAt, triggerAt)
    }

    fun scheduleWatchdog(reminderId: String, expectedAt: Long) {
        setAlarm(reminderId, expectedAt, System.currentTimeMillis() + PROCESSING_WATCHDOG_MS)
    }

    fun cancel(reminder: ReminderRecord) {
        val pending = PendingIntent.getBroadcast(appContext, reminder.id.hashCode(), alarmIntent(reminder.id, reminder.pendingAtMillis ?: reminder.nextAtMillis), pendingFlags())
        alarmManager.cancel(pending)
        pending.cancel()
    }

    fun defer(reminderId: String, expectedAt: Long, retryAt: Long, failedAttempt: Boolean) {
        store.defer(reminderId, expectedAt, retryAt, failedAttempt)?.let { updated ->
            if (updated.status == STATUS_SCHEDULED) schedule(updated)
            else ReminderNotifications.post(appContext, updated, ReminderNotificationKind.FAILED)
        }
    }

    fun deferForQuietHours(reminderId: String, expectedAt: Long, settings: ReminderSettings, zoneId: String) {
        val retryAt = ReminderTimePolicy.nextQuietEnd(System.currentTimeMillis(), settings, zoneId)
        store.deferQuiet(reminderId, expectedAt, retryAt)?.let(::schedule)
    }

    private fun alarmIntent(id: String, expectedAt: Long) = Intent(appContext, ReminderAlarmReceiver::class.java)
        .setAction(ACTION_REMINDER_ALARM)
        .setData(android.net.Uri.parse("companion-reminder://$id"))
        .putExtra(EXTRA_REMINDER_ID, id)
        .putExtra(EXTRA_EXPECTED_AT, expectedAt)

    private fun setAlarm(id: String, expectedAt: Long, triggerAt: Long) {
        val pending = PendingIntent.getBroadcast(appContext, id.hashCode(), alarmIntent(id, expectedAt), pendingFlags())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        else alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_REMINDER_DUE = "com.companion.assistant.REMINDER_DUE"
        const val ACTION_REMINDER_ALARM = "com.companion.assistant.REMINDER_ALARM"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_EXPECTED_AT = "expected_at"
        const val MIN_ALARM_DELAY_MS = 1_000L
        const val RETRY_DELAY_MS = 5 * 60_000L
        const val PROCESSING_WATCHDOG_MS = 2 * 60_000L
        const val STATUS_SCHEDULED = "scheduled"
    }
}

/** This flag is set only while the Activity is resumed; the alarm receiver still posts a notification as fallback. */
internal object ReminderHost {
    @Volatile var isVisible: Boolean = false
}

internal class ReminderCooldownStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("companion_reminder_delivery", Context.MODE_PRIVATE)

    fun nextAllowedAt(nowMillis: Long): Long = maxOf(nowMillis, preferences.getLong(KEY_LAST_SPOKEN, 0L) + COOLDOWN_MS)

    fun markSpoken(atMillis: Long) {
        preferences.edit().putLong(KEY_LAST_SPOKEN, atMillis).apply()
    }

    private companion object {
        const val KEY_LAST_SPOKEN = "last_proactive_speech"
        const val COOLDOWN_MS = 60_000L
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER_ALARM) return
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val expectedAt = intent.getLongExtra(ReminderScheduler.EXTRA_EXPECTED_AT, -1L)
        if (expectedAt <= 0L) return
        val store = ReminderStore(context)
        val settingsStore = ReminderSettingsStore(context)
        val scheduler = ReminderScheduler(context, store)
        val settings = settingsStore.get()
        if (!settings.autonomyEnabled) return
        val candidate = store.get(id) ?: return
        if (candidate.status != ReminderScheduler.STATUS_SCHEDULED) return
        val now = System.currentTimeMillis()
        val localZoneId = TimeZone.getDefault().id
        if (ReminderTimePolicy.isQuiet(now, settings, localZoneId)) {
            scheduler.deferForQuietHours(id, expectedAt, settings, localZoneId)
            return
        }
        val reminder = store.claimDue(id, expectedAt, now) ?: return
        if (reminder.status == "needs_attention") {
            if (ReminderNotifications.post(context, reminder, ReminderNotificationKind.FAILED)) {
                store.markDelivered(id, expectedAt, now)?.let { syncDeliveredReminder(context, it) }
                scheduler.scheduleAll()
            }
            return
        }
        if (reminder.status != ReminderScheduler.STATUS_SCHEDULED) return

        if (ReminderHost.isVisible) {
            scheduler.scheduleWatchdog(id, expectedAt)
            ReminderNotifications.post(context, reminder)
            context.sendBroadcast(Intent(ReminderScheduler.ACTION_REMINDER_DUE)
                .setPackage(context.packageName)
                .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, id)
                .putExtra(ReminderScheduler.EXTRA_EXPECTED_AT, expectedAt))
            return
        }
        if (ReminderNotifications.post(context, reminder)) {
            store.markDelivered(id, expectedAt, now)?.let { syncDeliveredReminder(context, it) }
            scheduler.scheduleAll()
        } else {
            scheduler.defer(id, expectedAt, now + ReminderScheduler.RETRY_DELAY_MS, failedAttempt = true)
        }
    }

    private fun syncDeliveredReminder(context: Context, reminder: ReminderRecord) {
        if (BuildConfig.COMPANION_BACKEND_URL.isBlank()) return
        val pendingResult = goAsync()
        Thread {
            val syncStore = ReminderStore(context.applicationContext)
            try {
                val tokenStore = CompanionAccessTokenStore(context.applicationContext)
                val count = ReminderSyncDispatcher(
                    syncStore,
                    ReminderApiClient(BuildConfig.COMPANION_BACKEND_URL, tokenStore::accessToken)
                ).flush()
                Log.i("ReminderSync", "background_outbox_flushed count=$count pending=${syncStore.pendingSyncCount()}")
            } catch (error: Exception) {
                val httpStatus = Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)
                val reason = httpStatus?.let { "http_$it" } ?: error.javaClass.simpleName
                Log.w("ReminderSync", "background_outbox_flush_failed reason=$reason")
            } finally {
                syncStore.close()
                pendingResult.finish()
            }
        }.start()
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val store = ReminderStore(context)
        store.resetInFlight()
        val scheduler = ReminderScheduler(context, store)
        val settings = ReminderSettingsStore(context).get()
        if (settings.autonomyEnabled) scheduler.scheduleAll()
    }
}

internal object ReminderNotifications {
    private const val CHANNEL_ID = "companion_reminders"
    private const val CHANNEL_NAME = "Hatırlatmalar"
    const val EXTRA_REMINDER_ID = "com.companion.assistant.extra.REMINDER_NOTIFICATION_ID"

    fun post(
        context: Context,
        reminder: ReminderRecord,
        kind: ReminderNotificationKind = ReminderNotificationKind.DUE
    ): Boolean {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                })
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) return false
            val copy = ReminderNotificationFormatter.format(reminder, kind)
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_REMINDER_ID, reminder.id)
            val contentIntent = PendingIntent.getActivity(
                context, reminder.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
            val notification = builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(copy.title)
                .setContentText(copy.body)
                .setStyle(Notification.BigTextStyle().bigText(copy.body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setDefaults(0)
                .build()
            manager.notify("reminder:${reminder.id}", reminder.id.hashCode(), notification)
            true
        } catch (error: Exception) {
            Log.w("ReminderDelivery", "notification_failed type=${error.javaClass.simpleName}")
            false
        }
    }

    fun cancel(context: Context, id: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel("reminder:$id", id.hashCode())
    }

    fun postTest(context: Context): Boolean {
        val sample = ReminderRecord("test-notification", "Hatırlatma bildirimi hazır.", System.currentTimeMillis(),
            TimeZone.getDefault().id, "none", "scheduled", System.currentTimeMillis(), System.currentTimeMillis(), null,
            System.currentTimeMillis(), null, 0, "idle")
        return post(context, sample)
    }
}

internal class ReminderCommandProcessor(
    private val store: ReminderStore,
    private val settingsStore: ReminderSettingsStore,
    private val scheduler: ReminderScheduler,
    private val onSettingsChanged: (ReminderSettings) -> Unit = {},
    private val syncReminder: (ReminderRecord, Boolean) -> Unit = { _, _ -> },
    private val syncDelete: (String) -> Unit = {}
) {
    fun process(transcript: String, nowMillis: Long = System.currentTimeMillis(), zoneId: String = TimeZone.getDefault().id): String? {
        val command = TurkishReminderCommandParser.parse(transcript, nowMillis, zoneId) ?: return null
        if (command is ReminderCommand.Create) {
            val clockFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z z", java.util.Locale.US).apply {
                timeZone = TimeZone.getTimeZone(zoneId)
            }
            Log.i("ReminderClock", "parsed nowEpoch=$nowMillis zone=$zoneId localNow=${clockFormat.format(nowMillis)} " +
                "targetEpoch=${command.atMillis} localTarget=${clockFormat.format(command.atMillis)}")
        }
        return when (command) {
            is ReminderCommand.Clarify -> command.question
            is ReminderCommand.Create -> {
                val record = try { store.create(command.message, command.atMillis, command.recurrence, zoneId) }
                catch (_: Exception) { return "Hatırlatmayı kaydedemedim. Lütfen daha sonra tekrar dene." }
                syncReminder(record, true)
                val settings = settingsStore.get()
                if (settings.autonomyEnabled) scheduler.schedule(record)
                val whenText = ReminderTimePolicy.formatLocal(record.nextAtMillis, zoneId)
                if (settings.autonomyEnabled) "Cihazıma kaydettim; ${record.message} için $whenText zamanında hatırlatacağım. Sunucu eşitlemesi bağlantı olduğunda tamamlanacak."
                else "Hatırlatmayı cihazıma $whenText için kaydettim. Teslim edilmesi için ayarlardan hatırlatmaları etkinleştirmen gerekiyor. Sunucu eşitlemesi bağlantı olduğunda tamamlanacak."
            }
            ReminderCommand.List -> {
                val reminders = store.list(includeCompleted = false).filter { it.status == "scheduled" || it.status == "paused" }.take(8)
                if (reminders.isEmpty()) "Kayıtlı yaklaşan hatırlatman yok."
                else reminders.joinToString(". ") { "${it.message}: ${ReminderTimePolicy.formatLocal(it.nextAtMillis, TimeZone.getDefault().id)}${if (it.status == "paused") ", duraklatıldı" else ""}" }
            }
            ReminderCommand.DisableAll -> {
                val settings = settingsStore.get().copy(autonomyEnabled = false)
                settingsStore.save(settings)
                scheduler.scheduleAll()
                onSettingsChanged(settings)
                "Hatırlatmaları kapattım. Kayıtların silinmedi."
            }
            ReminderCommand.EnableAll -> {
                val settings = settingsStore.get().copy(autonomyEnabled = true)
                settingsStore.save(settings)
                scheduler.scheduleAll()
                onSettingsChanged(settings)
                "Hatırlatmaları yeniden açtım."
            }
            is ReminderCommand.Delete -> changeMatching(command.query, delete = true)
            is ReminderCommand.Pause -> changeMatching(command.query, delete = false)
            is ReminderCommand.Complete -> completeMatching(command.query)
            is ReminderCommand.Snooze -> snoozeMatching(command.query, command.delayMillis)
        }
    }

    private fun completeMatching(query: String): String {
        val match = findMatching(query) ?: return "Bu ada uyan bir hatırlatma bulamadım."
        scheduler.cancel(match)
        store.complete(match.id)?.let { syncReminder(it, false) }
        return "${match.message} hatırlatmasını tamamlandı olarak işaretledim."
    }

    private fun snoozeMatching(query: String, delayMillis: Long): String {
        val match = findMatching(query) ?: return "Bu ada uyan bir hatırlatma bulamadım."
        val updated = store.snooze(match.id, delayMillis) ?: return "Bu hatırlatmayı erteleyemedim."
        scheduler.schedule(updated)
        syncReminder(updated, false)
        val minutes = delayMillis / 60_000L
        return "${match.message} hatırlatmasını $minutes dakika erteledim."
    }

    private fun findMatching(query: String): ReminderRecord? {
        val normalized = query.replace("hatırlatmasını", "").replace("hatırlatmayı", "").trim()
        if (normalized.isEmpty()) return null
        return store.list(includeCompleted = false).firstOrNull { reminder ->
            (reminder.status == "scheduled" || reminder.status == "paused") &&
                (reminder.message.contains(normalized, ignoreCase = true) || normalized.contains(reminder.message, ignoreCase = true))
        }
    }

    private fun changeMatching(query: String, delete: Boolean): String {
        val candidates = store.list(includeCompleted = false).filter { it.status == "scheduled" || it.status == "paused" }
        val normalized = query.replace("hatırlatmasını", "").replace("hatırlatmayı", "").trim()
        val match = candidates.firstOrNull { reminder ->
            normalized.isNotEmpty() && (reminder.message.contains(normalized, ignoreCase = true) || normalized.contains(reminder.message, ignoreCase = true))
        } ?: return if (normalized.isEmpty()) "Hangi hatırlatmayı kastettiğini söyler misin?" else "Bu ada uyan bir hatırlatma bulamadım."
        if (delete) {
            scheduler.cancel(match)
            store.delete(match.id)
            syncDelete(match.id)
            return "${match.message} hatırlatmasını sildim."
        }
        if (match.status == "scheduled") {
            scheduler.cancel(match)
            store.pause(match.id)?.let { syncReminder(it, false) }
            return "${match.message} hatırlatmasını duraklattım."
        }
        val resumed = store.resume(match.id) ?: return "Hatırlatmayı güncelleyemedim."
        scheduler.schedule(resumed)
        syncReminder(resumed, false)
        return "${match.message} hatırlatmasını yeniden açtım."
    }
}
