package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import android.provider.AlarmClock

class Phase74LocalCommandTest {
    @Test fun alarmTimeNeedsMorningOrEveningWhenAmbiguous() {
        assertTrue(TurkishAlarmCommandParser.parse("Ayşe saat 8'e alarm kur") is NativeAlarmCommand.Clarify)
        assertEquals(NativeAlarmCommand.Create(7, 0, emptyList(), true),
            TurkishAlarmCommandParser.parse("Ayşe yarın sabah 7'ye alarm kur"))
        assertEquals(20, (TurkishAlarmCommandParser.parse("Akşam saat 8'e alarm kur") as NativeAlarmCommand.Create).hour)
    }

    @Test fun recurringAlarmAndCompanionReminderStaySeparate() {
        val alarm = TurkishAlarmCommandParser.parse("Hafta içi her gün sabah 8'de alarm kur") as NativeAlarmCommand.Create
        assertEquals(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY), alarm.days)
        assertEquals(null, TurkishAlarmCommandParser.parse("2 dakika sonra su içmeyi hatırlat"))
    }

    @Test fun nativeAlarmIntentPlanCarriesTimeAndRepeatDays() {
        val command = NativeAlarmCommand.Create(8, 15, listOf(Calendar.MONDAY, Calendar.FRIDAY), false)
        val plan = NativeAlarmIntents.planFor(command)!!
        assertEquals(AlarmClock.ACTION_SET_ALARM, plan.action)
        assertEquals(8, plan.hour)
        assertEquals(15, plan.minute)
        assertEquals(listOf(Calendar.MONDAY, Calendar.FRIDAY), plan.days)
        assertTrue(plan.skipUi)
        assertEquals(null, NativeAlarmIntents.planFor(NativeAlarmCommand.Dismiss))
        assertEquals(AlarmClock.ACTION_SNOOZE_ALARM, NativeAlarmIntents.planFor(NativeAlarmCommand.Snooze)?.action)
    }

    @Test fun unsupportedIdentityRemainsUnknownAndProfilesAreExplicit() {
        assertEquals(SpeakerIdentity.Unknown, SpeakerIdentityPolicy.current())
        val profile = FamilyProfile("id1", "Ahmet", "baba", "Baba")
        val saved = FamilyProfileRules.save(emptyList(), profile)
        assertEquals(profile, saved.single())
        assertEquals("Ahmet", FamilyProfileRules.save(saved, profile.copy(preferences = "Çay" )).single().displayName)
        assertTrue(FamilyProfileRules.delete(saved, "id1").isEmpty())
        assertFalse(FamilyProfileRules.delete(saved, "other").isEmpty())
    }
}
