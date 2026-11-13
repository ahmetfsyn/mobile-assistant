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
    fun recognizesSpokenTimeAndDateQueriesWithOrWithoutWakeWord() {
        assertEquals(TurkishClockQueryParser.parse("Ayşe, saat kaç?"), TurkishClockQuery.TIME)
        assertEquals(TurkishClockQueryParser.parse("Ayşe, şu an saat kaç?"), TurkishClockQuery.TIME)
        assertEquals(TurkishClockQueryParser.parse("Saat kaç?"), TurkishClockQuery.TIME)
        assertEquals(TurkishClockQueryParser.parse("Ayşe, bugün günlerden ne?"), TurkishClockQuery.WEEKDAY)
        assertEquals(TurkishClockQueryParser.parse("Ayşe, bugünün tarihi ne?"), TurkishClockQuery.DATE)
        assertEquals(TurkishClockQueryParser.parse("AYŞE bugunun tarihi ne"), TurkishClockQuery.DATE)
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
    fun localCommandsHavePriorityOverGenericConversation() {
        val alarm = LocalActionResult(LocalActionStatus.SUCCESS, "alarm")
        val clock = LocalActionResult(LocalActionStatus.SUCCESS, "saat")
        assertEquals(LocalCommandRoute.NATIVE_ALARM, LocalCommandRouteSelector.select(alarm, clock, "hatırlatıcı"))
        assertEquals(LocalCommandRoute.DEVICE_CLOCK, LocalCommandRouteSelector.select(null, clock, "hatırlatıcı"))
        assertEquals(LocalCommandRoute.COMPANION_REMINDER, LocalCommandRouteSelector.select(null, null, "hatırlatıcı"))
        assertEquals(LocalCommandRoute.CONVERSATION_BACKEND, LocalCommandRouteSelector.select(null, null, null))
    }
}
