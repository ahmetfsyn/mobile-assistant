package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LocalClockCommandTest {
    @Test
    fun relativeAlarmParserSupportsNumericAndTurkishSpokenDurations() {
        assertEquals(NativeAlarmCommand.RelativeCreate(2),
            TurkishAlarmCommandParser.parse("Ayşe, 2 dakika sonrasına alarm kur."))
        assertEquals(NativeAlarmCommand.RelativeCreate(5),
            TurkishAlarmCommandParser.parse("5 dakika sonra alarm kur"))
        assertEquals(NativeAlarmCommand.RelativeCreate(30),
            TurkishAlarmCommandParser.parse("Yarım saat sonra alarm kur"))
        assertEquals(NativeAlarmCommand.RelativeCreate(60),
            TurkishAlarmCommandParser.parse("Bir saat sonra alarm kur"))
        assertEquals(NativeAlarmCommand.RelativeCreate(90),
            TurkishAlarmCommandParser.parse("Bir buçuk saat sonra alarm kur"))
        assertEquals(NativeAlarmCommand.RelativeCreate(45),
            TurkishAlarmCommandParser.parse("45 dakika sonra beni uyandır"))
        assertEquals(NativeAlarmCommand.RelativeCreate(3),
            TurkishAlarmCommandParser.parse("Üç dakika sonra alarm çalsın"))
        assertNull(TurkishAlarmCommandParser.parse("2 dakika sonra su içmeyi hatırlat"))
    }

    @Test
    fun recognizesSpokenTimeAndDateQueriesWithOrWithoutWakeWord() {
        assertEquals(TurkishClockQueryParser.parse("Ayşe, saat kaç?"), TurkishClockQuery.TIME)
        assertEquals(TurkishClockQueryParser.parse("Ayşe, şu an saat kaç?"), TurkishClockQuery.TIME)
        assertEquals(TurkishClockQueryParser.parse("Saat kaç?"), TurkishClockQuery.TIME)
        assertEquals(TurkishClockQueryParser.parse("Ayşe, bugün günlerden ne?"), TurkishClockQuery.WEEKDAY)
        assertEquals(TurkishClockQueryParser.parse("Ayşe, bugünün tarihi ne?"), TurkishClockQuery.DATE)
        assertEquals(TurkishClockQueryParser.parse("AYŞE bugunun tarihi ne"), TurkishClockQuery.DATE)
        assertEquals(TurkishClockQuery.DATE,
            TurkishClockQueryParser.parseCorrection("Ayşe, saat kaç?", "Hayır, tarihi soruyorum"))
        assertNull(TurkishClockQueryParser.parse("Ayşe, akşam 9'a alarm kur"))
        assertNull(TurkishClockQueryParser.parse("Bugün Ankara'da hava nasıl?"))
    }

    @Test
    fun alarmParserHandlesCapturedPunctuationAndTurkishNumberForms() {
        assertEquals(NativeAlarmCommand.Create(9, 0, emptyList(), false),
            TurkishAlarmCommandParser.parse("Ayşe, sabah 9'a alarm kur."))
        assertEquals(NativeAlarmCommand.Create(9, 0, emptyList(), false),
            TurkishAlarmCommandParser.parse("AYŞE sabah saat 9’a alarm kur!"))
        assertTrue(TurkishAlarmCommandParser.parse("saat 9'a alarm kur") is NativeAlarmCommand.Clarify)
        assertEquals(NativeAlarmCommand.Create(9, 0, emptyList(), true),
            TurkishAlarmCommandParser.parse("Ayşe, yarın sabah dokuz'a alarm kur."))
        assertEquals(NativeAlarmCommand.Create(21, 0, emptyList(), false),
            TurkishAlarmCommandParser.parse("Ayşe, akşam dokuz'a alarm kur."))
        assertEquals(NativeAlarmCommand.Create(0, 0, emptyList(), false),
            TurkishAlarmCommandParser.parse("Ayşe, gece 12’ye alarm kur."))
        assertEquals(NativeAlarmCommand.Create(21, 0, emptyList(), false),
            TurkishAlarmCommandParser.parseCorrection("Ayşe, akşam 8'e alarm kur", "Hayır, 9 olsun"))
    }

    @Test
    fun ambiguousHoursAskRatherThanInventingMorningOrEvening() {
        assertTrue(TurkishAlarmCommandParser.parse("Ayşe, saat 9'a alarm kur") is NativeAlarmCommand.Clarify)
        assertTrue(TurkishAlarmCommandParser.parse("Ayşe, saat dokuz'a alarm kur") is NativeAlarmCommand.Clarify)
    }

    @Test
    fun deterministicClockResponseUsesSuppliedLocalDeviceTime() {
        val zone = TimeZone.getTimeZone("Europe/Istanbul")
        val calendar = Calendar.getInstance(zone, Locale("tr", "TR")).apply {
            clear()
            set(2024, Calendar.MARCH, 20, 9, 15, 0)
        }
        val now = calendar.timeInMillis
        assertEquals("Şu anda saat dokuz on beş.",
            TurkishClockResponseFormatter.response(TurkishClockQuery.TIME, now, zone))
        assertEquals("Bugün günlerden çarşamba.",
            TurkishClockResponseFormatter.response(TurkishClockQuery.WEEKDAY, now, zone))
        assertEquals("Bugünün tarihi 20 Mart 2024.",
            TurkishClockResponseFormatter.response(TurkishClockQuery.DATE, now, zone))
    }

    @Test
    fun relativeAlarmCalculationUsesAbsoluteDeviceTimeAcrossDateAndNoonBoundaries() {
        val zone = TimeZone.getTimeZone("Europe/Istanbul")
        fun epoch(hour: Int, minute: Int, second: Int = 0): Long = Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, hour, minute, second)
        }.timeInMillis

        val atMorning = RelativeAlarmCalculator.calculate(epoch(9, 15, 42), 2, zone)!!
        assertEquals(epoch(9, 18), atMorning.epochMillis)
        assertEquals(9, atMorning.hour)
        assertEquals(18, atMorning.minute)

        val atEvening = RelativeAlarmCalculator.calculate(epoch(17, 3), 2, zone)!!
        assertEquals(17, atEvening.hour)
        assertEquals(5, atEvening.minute)

        val midnight = RelativeAlarmCalculator.calculate(epoch(23, 59), 2, zone)!!
        assertEquals("2026-11-14 00:01", midnight.localDateTime)
        assertEquals(0, midnight.hour)
        assertEquals(1, midnight.minute)

        val noon = RelativeAlarmCalculator.calculate(epoch(11, 58), 5, zone)!!
        assertEquals(12, noon.hour)
        assertEquals(3, noon.minute)

        val hourly = RelativeAlarmCalculator.calculate(epoch(20, 30), 60, zone)!!
        assertEquals(21, hourly.hour)
        assertEquals(30, hourly.minute)
    }

    @Test
    fun relativeAlarmTargetUsesTheProvidedTimezone() {
        val utc = TimeZone.getTimeZone("UTC")
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val midnightUtc = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, 15, 30)
        }.timeInMillis
        val tokyoTarget = RelativeAlarmCalculator.calculate(midnightUtc, 30, tokyo)!!
        assertEquals("2026-11-14 01:00", tokyoTarget.localDateTime)
        assertEquals(1, tokyoTarget.hour)
        assertEquals(0, tokyoTarget.minute)
    }

    @Test
    fun localCommandsHavePriorityOverGenericConversation() {
        val alarm = LocalActionResult(LocalActionStatus.LOCAL_SUCCESS, "alarm")
        val clock = LocalActionResult(LocalActionStatus.LOCAL_SUCCESS, "saat")
        assertEquals(LocalCommandRoute.NATIVE_ALARM, LocalCommandRouteSelector.select(alarm, clock, "hatırlatıcı"))
        assertEquals(LocalCommandRoute.DEVICE_CLOCK, LocalCommandRouteSelector.select(null, clock, "hatırlatıcı"))
        assertEquals(LocalCommandRoute.COMPANION_REMINDER, LocalCommandRouteSelector.select(null, null, "hatırlatıcı"))
        assertEquals(LocalCommandRoute.CONVERSATION_BACKEND, LocalCommandRouteSelector.select(null, null, null))
    }
}
