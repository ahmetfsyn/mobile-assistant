package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TurkishReminderCommandParserTest {
    @Test
    fun parsesRelativeTurkishReminderWithoutChangingItsRequestedDelay() {
        val now = 1_800_000_000_000L
        val command = TurkishReminderCommandParser.parse("Bir saat sonra kahvemi kontrol et", now, "Europe/Istanbul")
        assertTrue(command is ReminderCommand.Create)
        command as ReminderCommand.Create
        assertEquals(now + 60 * 60_000L, command.atMillis)
        assertEquals("kahvemi", command.message)
        assertEquals("none", command.recurrence)
    }

    @Test
    fun parsesTomorrowAndDailyTurkishSchedulesInLocalTime() {
        val zone = TimeZone.getTimeZone("Europe/Istanbul")
        val now = Calendar.getInstance(zone).apply {
            set(2030, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val tomorrow = TurkishReminderCommandParser.parse("Yarın saat 10'da toplantıyı hatırlat.", now, zone.id)
        assertTrue(tomorrow is ReminderCommand.Create)
        tomorrow as ReminderCommand.Create
        val actual = Calendar.getInstance(zone).apply { timeInMillis = tomorrow.atMillis }
        assertEquals(2, actual.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, actual.get(Calendar.HOUR_OF_DAY))
        assertTrue(tomorrow.message.contains("toplantı"))

        val daily = TurkishReminderCommandParser.parse("Her gün saat 20'de ilaç saatimi hatırlat", now, zone.id)
        assertTrue(daily is ReminderCommand.Create)
        assertEquals("daily", (daily as ReminderCommand.Create).recurrence)
    }

    @Test
    fun ambiguousOrHypotheticalRequestsNeverCreateAReminder() {
        val now = 1_800_000_000_000L
        val ambiguous = TurkishReminderCommandParser.parse("Akşam toplantıyı hatırlat", now, "Europe/Istanbul")
        assertTrue(ambiguous is ReminderCommand.Clarify)
        assertEquals(null, TurkishReminderCommandParser.parse("Yarın saat 10'da toplantı olsa güzel olur", now, "Europe/Istanbul"))
        assertEquals(null, TurkishReminderCommandParser.parse("Hatırlatıcılar nasıl çalışıyor?", now, "Europe/Istanbul"))
        assertEquals(null, TurkishReminderCommandParser.parse("Yarın saat 10'da toplantı hatırlatmam gerek", now, "Europe/Istanbul"))
    }

    @Test
    fun quietHoursCrossMidnightAndDeferToTheNextLocalEnd() {
        val zone = TimeZone.getTimeZone("Europe/Istanbul")
        val now = Calendar.getInstance(zone).apply {
            set(2030, Calendar.JANUARY, 2, 2, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val settings = ReminderSettings(quietHoursEnabled = true, quietHoursStart = "23:00", quietHoursEnd = "08:00")
        assertTrue(ReminderTimePolicy.isQuiet(now, settings, zone.id))
        val end = Calendar.getInstance(zone).apply { timeInMillis = ReminderTimePolicy.nextQuietEnd(now, settings, zone.id) }
        assertEquals(2, end.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, end.get(Calendar.HOUR_OF_DAY))
        assertFalse(ReminderTimePolicy.isQuiet(end.timeInMillis, settings, zone.id))
    }

    @Test
    fun weekdayRecurrenceSkipsWeekendAndOneTimeHasNoNextOccurrence() {
        val zone = TimeZone.getTimeZone("Europe/Istanbul")
        val friday = Calendar.getInstance(zone).apply {
            set(2030, Calendar.JANUARY, 4, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val monday = ReminderTimePolicy.nextOccurrence(friday, "weekdays", zone.id, friday)
        assertTrue(monday != null)
        assertEquals(Calendar.MONDAY, Calendar.getInstance(zone).apply { timeInMillis = monday!! }.get(Calendar.DAY_OF_WEEK))
        assertEquals(null, ReminderTimePolicy.nextOccurrence(friday, "none", zone.id, friday))
    }
}
