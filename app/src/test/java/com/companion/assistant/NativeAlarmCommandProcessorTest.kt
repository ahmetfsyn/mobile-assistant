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
        assertEquals(LocalActionStatus.SUCCESS, result.status)
        assertEquals(0, launcher.launchCount)
        assertEquals("Saat uygulaması alarm isteğini açamadı. Lütfen uygulamadaki Saat ayarlarını kontrol et.", result.afterSpeech?.invoke())
        assertEquals(1, launcher.launchCount)
        assertTrue(result.message.contains("kurma isteğini gönderiyorum"))
        assertTrue(diagnostics.warnings.any { "alarm_intent_launch_failed" in it })
    }

    @Test
    fun nativeAlarmRequestUsesResolvedClockAndCorrectAlarmExtras() {
        val launcher = FakeLauncher(target = "com.google.android.deskclock/.HandleSetApiCalls")
        val result = processor(launcher).process("Ayşe, gece 12'ye alarm kur.")!!
        assertEquals(LocalActionStatus.SUCCESS, result.status)
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

    private fun processor(launcher: FakeLauncher, diagnostics: RecordingDiagnostics = RecordingDiagnostics()) = NativeAlarmCommandProcessor(
        launcher = launcher,
        nowCalendar = {
            Calendar.getInstance(TimeZone.getTimeZone("Europe/Istanbul"), Locale("tr", "TR")).apply {
                clear()
                set(2026, Calendar.NOVEMBER, 13, 20, 0, 0)
            }
        },
        diagnostics = diagnostics
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
}
