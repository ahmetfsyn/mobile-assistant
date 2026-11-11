# Phase 6 — Controlled reminders

## Architecture

The phone is the delivery authority. `ReminderStore` keeps reminder definitions and occurrence state in a small app-private SQLite database. `ReminderScheduler` uses `AlarmManager.setAndAllowWhileIdle` on API 23+ and inexact `RTC_WAKEUP` alarms; it does not keep a service or wake lock running between alarms. The existing Node backend has versioned SQLite migration 2 and authenticated reminder/settings routes as an optional mirror. A failed or unavailable backend sync never prevents local reminder creation or delivery.

`ReminderCommandProcessor` sees only the current, nonempty recognized transcript. It handles explicit Turkish scheduling and reminder-management commands locally before ordinary text can reach Gemini. It does not infer a reminder from a hypothetical statement. The parser uses the device timezone and `Calendar`, so it works on Android 7/API 24 without Java time desugaring. A request without a clear future time asks the user to clarify and writes nothing.

Supported initial scheduling forms include:

- “Yarın saat 10'da toplantıyı hatırlat.”
- “30 dakika sonra su içmemi hatırlat.”
- “Bir saat sonra kahvemi kontrol et.”
- “Her sabah saat 9'da beni selamla.”
- “Her pazartesi saat 10'da haftalık planımı hatırlat.”
- “Her gün saat 20'de ilaç saatimi hatırlat.”
- “Bugünkü hatırlatmalarım neler?”, “Hatırlatmaları kapat/aç”, and reminder pause, resume, complete, delete, and explicit-duration snooze commands.

The UI supports creating, editing, pausing, resuming, completing, deleting, retrying, and reviewing recent reminders. Recurrence options are one-time, daily, weekdays, and weekly on a selected weekday.

## Consent, quiet hours, and delivery

- Autonomy defaults off; proactive speech defaults off; quiet hours default on from 23:00 to 08:00. No greeting is scheduled automatically.
- Autonomy off cancels alarms but leaves saved reminders intact. Turning it on re-arms them.
- Quiet hours retain the due occurrence and schedule it for the next permitted local window. The reminder keeps its original scheduled time for status/history.
- If the Activity is foregrounded, speech is attempted only when `RobotCore` is idle and the consent gates allow it. Listening, thinking, speaking, a closed host, and a one-minute proactive-speech cooldown defer the occurrence instead of interrupting the turn.
- If the app is not foregrounded, an Android notification is the delivery fallback. Notifications are silent. If notification delivery is disabled, a bounded retry is attempted and the reminder remains available for manual retry.
- TTS failures retry at five-minute intervals up to three attempts. After exhaustion the reminder remains visible as requiring attention; a notification can complete delivery when available. Duplicate broadcasts are guarded by the stored occurrence and processing state. A two-minute watchdog allows recovery if the process dies during a delivery attempt.
- Recurring schedules advance only after a delivery succeeds. Calendar-based recurrence retains the selected timezone and local wall-clock time across daylight-saving transitions. Boot, app replacement, manual clock changes, and timezone changes re-arm pending alarms.
- No reminder triggers an AI request. Reminder text is sent only to the local backend mirror when configured; local alarm handling does not use the network.

## Backend API and database

The existing SQLite file gets an additive schema migration. Existing memory rows are preserved.

- `GET/POST /v1/reminders`
- `PATCH/DELETE /v1/reminders/:id`
- `GET/PUT /v1/autonomy`

Routes use the existing optional companion bearer-token gate, request-rate limit, body-size limit, normalized error responses, and JSON `no-store` headers. Reminder payloads validate the message length, UUID, IANA timezone, recurrence, timestamps, status, and quiet-hour values. Default backend settings match the safe Android defaults. The backend is not a scheduler and does not initiate background AI calls.

## Android lifecycle and known limits

Alarms are inexact by design. The OS can move an alarm later to conserve power; Android 7 and manufacturer battery managers may delay background delivery further. Reboot rescheduling requires the app to have been launched once after install. Android clears the app's local SQLite data if the user clears app data or uninstalls. The backend mirror is best effort and is not automatically imported as a second source of truth.

Time and timezone broadcasts preserve the saved local timezone and re-arm epoch-based alarms. Device changes to the timezone therefore do not silently reinterpret an existing reminder; edit the reminder to choose a different timezone schedule.

## Manual GM 5 Plus verification

1. Open **Security & Privacy → Autonomy and Reminders**. Confirm autonomy and proactive speech are off and quiet hours are on.
2. Enable autonomy and proactive speech. Add a one-time reminder two minutes ahead, then leave the face screen open and idle; confirm one spoken delivery and a completed history entry.
3. Add a reminder while the face is listening or speaking; verify it waits until a later idle window and does not overlap the user turn.
4. Schedule a reminder during quiet hours; confirm it remains saved and is delivered after the configured end time, with its original due time retained.
5. Pause, resume, edit, complete, delete, and use the manual retry action on a test reminder.
6. Reboot the phone with a future reminder pending. Confirm it remains scheduled after the robot app has been opened following reboot.
7. Force-stop the Activity/process, then allow a reminder to become due; verify the notification fallback. Test notification disabled in Android system settings and confirm bounded retry/manual attention behavior.
8. Change timezone and wall clock while a future reminder is pending. Confirm the schedule is re-armed without losing its stored timezone.
9. Disable autonomy and confirm saved reminders remain but no alarm speech is initiated.

The current delivery has automated source/unit/API coverage and successful APK builds. The manual device checks above have not been claimed as completed unless recorded separately.
