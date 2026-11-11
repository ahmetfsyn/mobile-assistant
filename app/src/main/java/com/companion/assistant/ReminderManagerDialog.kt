package com.companion.assistant

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal class ReminderManagerDialog(
    private val activity: Activity,
    private val store: ReminderStore,
    private val settingsStore: ReminderSettingsStore,
    private val scheduler: ReminderScheduler,
    private val onSettingsChanged: (ReminderSettings) -> Unit,
    private val syncReminder: (ReminderRecord, Boolean) -> Unit,
    private val syncDelete: (String) -> Unit
) {
    private var dialog: AlertDialog? = null

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun show() {
        if (activity.isFinishing) return
        var settings = settingsStore.get()
        val density = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), 0)
        }
        val content = ScrollView(activity).apply { addView(root) }
        val autonomy = CheckBox(activity).apply {
            text = "Hatırlatmaları etkinleştir"
            isChecked = settings.autonomyEnabled
        }
        val speech = CheckBox(activity).apply {
            text = "Uygulama açıkken hatırlatmaları sesli söyle"
            isChecked = settings.proactiveSpeechEnabled
        }
        val quiet = CheckBox(activity).apply {
            text = "Sessiz saatleri uygula"
            isChecked = settings.quietHoursEnabled
        }
        root.addView(autonomy)
        root.addView(speech)
        root.addView(quiet)
        root.addView(TextView(activity).apply {
            text = "Varsayılan sessiz saatler 23:00–08:00. Sessiz saatlerde hatırlatma sonraki izinli zamana ertelenir."
            setTextColor(Color.GRAY)
        })
        val quietTimes = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val startButton = Button(activity).apply { text = "Başlangıç ${settings.quietHoursStart}" }
        val endButton = Button(activity).apply { text = "Bitiş ${settings.quietHoursEnd}" }
        quietTimes.addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        quietTimes.addView(endButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(quietTimes)
        root.addView(TextView(activity).apply { text = "HATIRLATICILAR"; setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt()) })
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        val add = Button(activity).apply { text = "HATIRLATMA EKLE" }
        root.addView(add)

        fun applySettings() {
            settings = settings.copy(
                autonomyEnabled = autonomy.isChecked,
                proactiveSpeechEnabled = speech.isChecked,
                quietHoursEnabled = quiet.isChecked
            )
            settingsStore.save(settings)
            onSettingsChanged(settings)
        }

        fun renderList() {
            list.removeAllViews()
            val reminders = store.list().filter { it.status != "deleted" }.take(25)
            if (reminders.isEmpty()) {
                list.addView(TextView(activity).apply { text = "Henüz hatırlatma yok."; setTextColor(Color.GRAY) })
                return
            }
            reminders.forEach { reminder ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
                }
                val title = TextView(activity).apply {
                    val date = if (reminder.lastDeliveredAtMillis != null && reminder.status == "delivered")
                        "Teslim edildi: ${ReminderTimePolicy.formatLocal(reminder.lastDeliveredAtMillis, reminder.timeZoneId)}"
                    else "${ReminderTimePolicy.formatLocal(reminder.nextAtMillis, reminder.timeZoneId)} · ${reminder.recurrenceLabel()}"
                    text = "${reminder.message}\n$date · ${reminder.statusLabel()}"
                    setTextColor(Color.WHITE)
                }
                row.addView(title)
                val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                fun action(label: String, click: () -> Unit) = Button(activity).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(0xFF223442.toInt())
                    setOnClickListener { click() }
                }
                actions.addView(action("DÜZENLE") {
                    applySettings()
                    dialog?.dismiss()
                    showEditor(reminder)
                })
                if (reminder.status == "paused") actions.addView(action("AÇ") {
                    val updated = store.resume(reminder.id)
                    if (updated != null) {
                        scheduler.schedule(updated)
                        syncReminder(updated, false)
                    }
                    renderList()
                }) else if (reminder.status == "scheduled") actions.addView(action("DURAKLAT") {
                    scheduler.cancel(reminder)
                    val updated = store.pause(reminder.id)
                    if (updated != null) syncReminder(updated, false)
                    renderList()
                })
                if (reminder.status == "needs_attention") actions.addView(action("TEKRAR DENE") {
                    val updated = store.resume(reminder.id)
                    if (updated != null) {
                        scheduler.schedule(updated)
                        syncReminder(updated, false)
                    }
                    renderList()
                })
                if (reminder.status == "scheduled" || reminder.status == "paused") actions.addView(action("TAMAMLA") {
                    scheduler.cancel(reminder)
                    val updated = store.complete(reminder.id)
                    if (updated != null) syncReminder(updated, false)
                    renderList()
                })
                actions.addView(action("SİL") {
                    scheduler.cancel(reminder)
                    store.delete(reminder.id)
                    syncDelete(reminder.id)
                    renderList()
                })
                row.addView(actions)
                list.addView(row)
            }
        }

        fun selectQuietTime(isStart: Boolean) {
            val value = if (isStart) settings.quietHoursStart else settings.quietHoursEnd
            val parts = value.split(":")
            TimePickerDialog(activity, { _, hour, minute ->
                val chosen = "%02d:%02d".format(hour, minute)
                settings = if (isStart) settings.copy(quietHoursStart = chosen) else settings.copy(quietHoursEnd = chosen)
                startButton.text = "Başlangıç ${settings.quietHoursStart}"
                endButton.text = "Bitiş ${settings.quietHoursEnd}"
                applySettings()
            }, parts[0].toInt(), parts[1].toInt(), true).show()
        }
        startButton.setOnClickListener { selectQuietTime(true) }
        endButton.setOnClickListener { selectQuietTime(false) }
        autonomy.setOnCheckedChangeListener { _, _ -> applySettings() }
        speech.setOnCheckedChangeListener { _, _ -> applySettings() }
        quiet.setOnCheckedChangeListener { _, _ -> applySettings() }
        add.setOnClickListener {
            applySettings()
            dialog?.dismiss()
            showEditor(null)
        }
        renderList()

        val created = AlertDialog.Builder(activity)
            .setTitle("OTONOMİ VE HATIRLATICILAR")
            .setView(content)
            .setPositiveButton("KAYDET", null)
            .setNeutralButton("TEST BİLDİRİMİ", null)
            .setNegativeButton("KAPAT", null)
            .create()
        dialog = created
        created.setOnDismissListener { if (dialog === created) dialog = null }
        created.setOnShowListener {
            created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                applySettings()
                Toast.makeText(activity, "Tercihler kaydedildi.", Toast.LENGTH_SHORT).show()
                created.dismiss()
            }
            created.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!ReminderNotifications.postTest(activity)) Toast.makeText(activity, "Bildirim gösterilemiyor. Sistem bildirim izinlerini kontrol et.", Toast.LENGTH_LONG).show()
                else Toast.makeText(activity, "Test bildirimi gönderildi.", Toast.LENGTH_SHORT).show()
            }
            makeImmersive(created)
        }
        created.show()
    }

    private fun showEditor(existing: ReminderRecord?) {
        if (activity.isFinishing) return
        val density = activity.resources.displayMetrics.density
        val zone = TimeZone.getDefault().id
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            timeInMillis = existing?.scheduledAtMillis ?: (System.currentTimeMillis() + 10 * 60_000L)
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
        }
        val message = EditText(activity).apply {
            hint = "Ne hatırlatayım?"
            setText(existing?.message.orEmpty())
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(240))
        }
        val dateButton = Button(activity)
        val timeButton = Button(activity)
        fun refreshDateTime() {
            val dateText = java.text.SimpleDateFormat("dd MMM yyyy", Locale("tr", "TR")).apply {
                timeZone = TimeZone.getTimeZone(zone)
            }.format(calendar.timeInMillis)
            dateButton.text = "TARİH · $dateText"
            timeButton.text = "SAAT · %02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
        }
        dateButton.setOnClickListener {
            DatePickerDialog(activity, { _, year, month, day ->
                calendar.set(Calendar.YEAR, year); calendar.set(Calendar.MONTH, month); calendar.set(Calendar.DAY_OF_MONTH, day)
                refreshDateTime()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeButton.setOnClickListener {
            TimePickerDialog(activity, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                refreshDateTime()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }
        refreshDateTime()
        val recurrenceLabels = listOf("Bir defa", "Her gün", "Hafta içi", "Her pazartesi", "Her salı", "Her çarşamba", "Her perşembe", "Her cuma", "Her cumartesi", "Her pazar")
        val recurrenceValues = listOf("none", "daily", "weekdays", "weekly:MO", "weekly:TU", "weekly:WE", "weekly:TH", "weekly:FR", "weekly:SA", "weekly:SU")
        val recurrence = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, recurrenceLabels)
            setSelection(recurrenceValues.indexOf(existing?.recurrence ?: "none").coerceAtLeast(0))
        }
        form.addView(message)
        form.addView(dateButton)
        form.addView(timeButton)
        form.addView(TextView(activity).apply { text = "TEKRAR"; setPadding(0, (6 * density).toInt(), 0, 0) })
        form.addView(recurrence)
        val editor = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "YENİ HATIRLATMA" else "HATIRLATMAYI DÜZENLE")
            .setView(form)
            .setPositiveButton("KAYDET", null)
            .setNegativeButton("İPTAL", null)
            .create()
        dialog = editor
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    message.error = "Bir hatırlatma metni yaz."
                    return@setOnClickListener
                }
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(activity, "Gelecekte bir tarih ve saat seç.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                try {
                    val record = if (existing == null) store.create(text, calendar.timeInMillis, recurrenceValues[recurrence.selectedItemPosition], zone)
                    else store.update(existing.id, text, calendar.timeInMillis, recurrenceValues[recurrence.selectedItemPosition])
                    if (record == null) throw IllegalStateException("Hatırlatma bulunamadı.")
                    scheduler.schedule(record)
                    syncReminder(record, existing == null)
                    Toast.makeText(activity, "Hatırlatma kaydedildi.", Toast.LENGTH_SHORT).show()
                    editor.dismiss()
                    show()
                } catch (_: Exception) {
                    Toast.makeText(activity, "Hatırlatma kaydedilemedi.", Toast.LENGTH_LONG).show()
                }
            }
            makeImmersive(editor)
        }
        editor.setOnDismissListener { dialog = null }
        editor.show()
    }

    private fun makeImmersive(dialog: AlertDialog) {
        @Suppress("DEPRECATION")
        dialog.window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun ReminderRecord.statusLabel(): String = when (status) {
        "scheduled" -> "etkin"
        "paused" -> "duraklatıldı"
        "delivered" -> "teslim edildi"
        "completed" -> "tamamlandı"
        "needs_attention" -> "teslim edilemedi"
        else -> status
    }

    private fun ReminderRecord.recurrenceLabel(): String = when (recurrence) {
        "daily" -> "her gün"
        "weekdays" -> "hafta içi"
        "weekly:MO" -> "her pazartesi"
        "weekly:TU" -> "her salı"
        "weekly:WE" -> "her çarşamba"
        "weekly:TH" -> "her perşembe"
        "weekly:FR" -> "her cuma"
        "weekly:SA" -> "her cumartesi"
        "weekly:SU" -> "her pazar"
        else -> "tek seferlik"
    }
}
