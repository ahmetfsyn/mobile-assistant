package com.companion.assistant

import android.provider.AlarmClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class NativeAlarmCommandProcessorTest {
    @Test
    fun missingClockHandlerDoesNotAnnounceSuccessOrAttemptLaunch() {
        val launcher = FakeLauncher(target = null)
        val result = processor(launcher).process("Ayşe, sabah 9'a alarm kur.")!!
        assertEquals(LocalActionStatus.UNSUPPORTED, result.status)
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun failedAndroidLaunchIsReportedAsFailure() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleSetApiCalls", failLaunch = true)
        val diagnostics = RecordingDiagnostics()
        val result = processor(launcher, diagnostics).process("Ayşe, sabah 9'a alarm kur.")!!
        assertEquals(LocalActionStatus.ACTION_DISPATCHED, result.status)
        assertEquals(0, launcher.launchCount)
        assertEquals("Saat uygulaması alarm isteğini açamadı. Lütfen uygulamadaki Saat ayarlarını kontrol et.", result.afterSpeech?.invoke())
        assertEquals(1, launcher.launchCount)
        assertTrue(result.message.contains("alarm kurma isteğini gönderiyorum"))
        assertTrue(diagnostics.warnings.any { "alarm_intent_launch_failed" in it })
    }

    @Test
    fun nativeAlarmRequestUsesResolvedClockAndCorrectAlarmExtras() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleSetApiCalls")
        val result = processor(launcher).process("Ayşe, gece 12'ye alarm kur.")!!
        assertEquals(LocalActionStatus.ACTION_DISPATCHED, result.status)
        assertFalse(launcher.launched)
        assertEquals(AlarmClock.ACTION_SET_ALARM, launcher.plan?.action)
        assertEquals(0, launcher.plan?.hour)
        assertEquals(0, launcher.plan?.minute)
        assertTrue(launcher.plan?.skipUi == true)
        assertTrue(result.message.contains("00.00"))
        assertEquals(null, result.afterSpeech?.invoke())
        assertTrue(launcher.launched)
    }

    @Test
    fun unrelatedTranscriptDoesNotReachNativeAlarmHandler() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleSetApiCalls")
        assertEquals(null, processor(launcher).process("Bugün hava nasıl?"))
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun relativeAlarmIsCalculatedFromDeviceClockBeforeBuildingIntent() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleSetApiCalls")
        val result = processor(launcher).process("Ayşe, 2 dakika sonrasına alarm kur.")!!
        assertEquals(LocalActionStatus.ACTION_DISPATCHED, result.status)
        assertEquals(AlarmClock.ACTION_SET_ALARM, launcher.plan?.action)
        assertEquals(20, launcher.plan?.hour)
        assertEquals(2, launcher.plan?.minute)
        assertTrue(result.message.contains("20.02"))
    }

    @Test
    fun disablingAllAlarmsUsesTheManagementGatewayInsteadOfOpeningClock() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleSetApiCalls")
        val gateway = FakeManagement(LocalActionResult(LocalActionStatus.LOCAL_SUCCESS, "3 alarm doğrulanarak kapatıldı."))
        val result = processor(launcher, management = gateway).process("Aktif alarmları kapat.")!!
        assertEquals(NativeAlarmCommand.DisableAll, TurkishAlarmCommandParser.parse("Aktif alarmları kapat."))
        assertEquals(LocalActionStatus.LOCAL_SUCCESS, result.status)
        assertTrue(result.message.contains("3 alarm"))
        assertTrue(gateway.called)
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun dismissingAlarmUsesDocumentedSearchModeWithoutClaimingVerification() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleApiCalls")
        val result = processor(launcher).process("Ayşe, çalan alarmı kapat.")!!
        assertEquals(LocalActionStatus.ACTION_DISPATCHED, result.status)
        assertTrue(result.message.contains("susturma isteğini"))
        assertEquals(AlarmClock.ACTION_DISMISS_ALARM, launcher.plan?.action)
        assertEquals(AlarmClock.ALARM_SEARCH_MODE_NEXT, launcher.plan?.alarmSearchMode)
        assertEquals(null, result.afterSpeech?.invoke())
        assertEquals(1, launcher.launchCount)
    }

    @Test
    fun relativeAlarmRoundsUpToClockMinutePrecisionInsteadOfFiringEarly() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("Europe/Istanbul"), Locale("tr", "TR")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, 20, 0, 45)
        }.timeInMillis
        val target = RelativeAlarmCalculator.calculate(now, 2, TimeZone.getTimeZone("Europe/Istanbul"))!!
        assertEquals("2026-11-13 20:03", target.localDateTime)
        assertEquals(20, target.hour)
        assertEquals(3, target.minute)
    }

    @Test
    fun relativeAlarmParserSupportsTurkishDurationPhrasingWithoutAmPmClarification() {
        val cases = listOf(
            "Ayşe, 2 dakika sonrasına alarm kur." to 2,
            "5 dakika sonra alarm kur." to 5,
            "45 dakika sonra beni uyandır." to 45,
            "Yarım saat sonra alarm kur." to 30,
            "Bir saat sonra alarm kur." to 60,
            "Bir buçuk saat sonra alarm kur." to 90,
            "Üç dakika sonra alarm çalsın." to 3
        )
        cases.forEach { (transcript, minutes) ->
            assertEquals(transcript, NativeAlarmCommand.RelativeCreate(minutes), TurkishAlarmCommandParser.parse(transcript))
        }
    }

    @Test
    fun alarmManagementParserDistinguishesListDisableDeleteAndEdit() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleApiCalls")
        val cases = listOf(
            "Saat 9 alarmını kapat." to NativeAlarmCommand.DisableOne(AlarmTarget(540, timeIsAmbiguous = true)),
            "Saat 9 alarmını sil." to NativeAlarmCommand.DeleteOne(AlarmTarget(540, timeIsAmbiguous = true)),
            "Aktif alarmları sil." to NativeAlarmCommand.DeleteAll(enabledOnly = true),
            "Bütün alarmları sil." to NativeAlarmCommand.DeleteAll(enabledOnly = false),
            "Saat 9 alarmını 10'a değiştir." to NativeAlarmCommand.EditOne(AlarmTarget(540, timeIsAmbiguous = true), 10, 0),
            "Saat 9 alarmını 10'a al." to NativeAlarmCommand.EditOne(AlarmTarget(540, timeIsAmbiguous = true), 10, 0),
            "Alarmları göster." to NativeAlarmCommand.ListAlarms(enabledOnly = false),
            "Aktif alarmları listele." to NativeAlarmCommand.ListAlarms(enabledOnly = true),
            "Bütün alarmları etkinleştir." to NativeAlarmCommand.EnableAll,
            "Aktif alarmları kapat." to NativeAlarmCommand.DisableAll
        )
        cases.forEach { (transcript, expected) ->
            assertEquals(expected, TurkishAlarmCommandParser.parse(transcript))
        }
        assertTrue(TurkishAlarmCommandParser.parse("Alarmı sil.") is NativeAlarmCommand.Clarify)
        assertTrue(TurkishAlarmCommandParser.parse("Aktif alarmı sil.") is NativeAlarmCommand.Clarify)
        assertEquals(
            NativeAlarmCommand.DisableOne(AlarmTarget(21 * 60)),
            TurkishAlarmCommandParser.parse("Akşam saat 9 alarmını kapat.")
        )
        assertEquals(
            NativeAlarmCommand.DisableOne(AlarmTarget(21 * 60)),
            TurkishAlarmCommandParser.parse("Akşam dokuz alarmını kapat.")
        )
        assertEquals(
            NativeAlarmCommand.DisableOne(AlarmTarget(21 * 60)),
            TurkishAlarmCommandParser.parse("Gece dokuz alarmını kapat.")
        )
        val unsupported = processor(launcher).process("Aktif alarmları kapat.")!!
        assertEquals(LocalActionStatus.UNSUPPORTED, unsupported.status)
        assertTrue(unsupported.message.contains("Alarm Erişimi"))
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun showAndSnoozeUseOnlyTheirDocumentedClockIntents() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleApiCalls")
        val show = processor(launcher, management = FakeManagement(LocalActionResult(LocalActionStatus.LOCAL_SUCCESS, "Alarmlar listelendi.")))
            .process("Alarmları göster.")!!
        assertEquals(LocalActionStatus.LOCAL_SUCCESS, show.status)
        assertTrue(show.message.contains("listelendi"))

        val snooze = processor(launcher).process("Çalan alarmı ertele.")!!
        assertEquals(LocalActionStatus.ACTION_DISPATCHED, snooze.status)
        assertTrue(snooze.message.contains("doğrulayamıyorum"))
        assertEquals(null, snooze.afterSpeech?.invoke())
        assertEquals(AlarmClock.ACTION_SNOOZE_ALARM, launcher.plan?.action)
        assertEquals(1, launcher.launchCount)
    }

    private fun processor(launcher: FakeLauncher, diagnostics: RecordingDiagnostics = RecordingDiagnostics(), management: AlarmManagementGateway? = null) = NativeAlarmCommandProcessor(
        launcher = launcher,
        nowCalendar = {
            Calendar.getInstance(TimeZone.getTimeZone("Europe/Istanbul"), Locale("tr", "TR")).apply {
                clear()
            set(2026, Calendar.NOVEMBER, 13, 20, 0, 0)
            }
        },
        diagnostics = diagnostics,
        management = management
    )

    private class FakeLauncher(
        private val target: String?,
        private val failLaunch: Boolean = false
    ) : NativeAlarmLauncher {
        var launchCount = 0
        var launched = false
        var plan: NativeAlarmIntents.Plan? = null

        override fun resolve(plan: NativeAlarmIntents.Plan): String? {
            this.plan = plan
            return target
        }

        override fun launch(plan: NativeAlarmIntents.Plan) {
            launchCount++
            this.plan = plan
            if (failLaunch) throw IllegalStateException("launch failed")
            launched = true
        }
    }

    private class RecordingDiagnostics : NativeAlarmDiagnostics {
        val warnings = mutableListOf<String>()
        override fun info(message: String) = Unit
        override fun warning(message: String) { warnings += message }
    }

    private class FakeManagement(private val result: LocalActionResult) : AlarmManagementGateway {
        var called = false
        override fun execute(command: NativeAlarmCommand, interactionId: String): LocalActionResult {
            called = true
            return result
        }
    }
}
