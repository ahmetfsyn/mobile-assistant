package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmManagementPolicyTest {
    private val morning = AlarmEntry(9 * 60, "09.00", "İlaç", enabled = true)
    private val evening = AlarmEntry(21 * 60, "21.00", "", enabled = true)

    @Test
    fun listsEnabledAndDisabledAlarmsWithoutChangingThem() {
        val result = AlarmManagementPolicy.listSummary(listOf(morning, evening.copy(enabled = false)), enabledOnly = true)
        assertEquals("Etkin alarmlar: 09.00, İlaç açık.", result)
        assertFalse(AlarmManagementPolicy.listSummary(emptyList(), enabledOnly = false).contains("açık"))
    }

    @Test
    fun selectsByTimeOrLabelAndRequiresClarificationForAmbiguousMatch() {
        assertTrue(AlarmManagementPolicy.select(listOf(morning, evening), AlarmTarget(540)) is AlarmMatch.One)
        assertTrue(AlarmManagementPolicy.select(listOf(morning, evening), AlarmTarget(540, timeIsAmbiguous = true)) is AlarmMatch.Many)
        assertTrue(AlarmManagementPolicy.select(listOf(morning), AlarmTarget(label = "ilaç")) is AlarmMatch.One)
        assertTrue(AlarmManagementPolicy.select(listOf(morning, morning.copy(label = "İlaç 2")), AlarmTarget(540)) is AlarmMatch.Many)
        assertEquals(AlarmMatch.None, AlarmManagementPolicy.select(listOf(morning), AlarmTarget(600)))
    }

    @Test
    fun enableAndDisableOnlyCountAsSuccessfulAfterStateReadback() {
        assertTrue(AlarmManagementPolicy.enabledStateVerified(morning.copy(enabled = false), false))
        assertFalse(AlarmManagementPolicy.enabledStateVerified(morning, false))
        assertFalse(AlarmManagementPolicy.enabledStateVerified(null, true))
    }

    @Test
    fun deleteRequiresTheAlarmToDisappearFromTheList() {
        assertTrue(AlarmManagementPolicy.deletionVerified(listOf(morning), emptyList(), morning))
        assertFalse(AlarmManagementPolicy.deletionVerified(listOf(morning), listOf(morning), morning))
    }

    @Test
    fun editRequiresOldAlarmRemovalAndTheNewTimeToAppear() {
        assertTrue(AlarmManagementPolicy.editVerified(listOf(morning), listOf(morning.copy(timeMinutes = 600, displayTime = "10.00")), morning, 600))
        assertFalse(AlarmManagementPolicy.editVerified(listOf(morning), listOf(morning), morning, 600))
        assertFalse(AlarmManagementPolicy.editVerified(listOf(morning), emptyList(), morning, 600))
    }

    @Test
    fun partialBatchFailureIsReportedAsFailureAndAccessibilityMustBeEnabled() {
        assertEquals(LocalActionStatus.LOCAL_SUCCESS, AlarmManagementPolicy.batchStatus(3, 0))
        assertEquals(LocalActionStatus.LOCAL_FAILURE, AlarmManagementPolicy.batchStatus(2, 1))
        val unavailable = AccessibilityAlarmManagementGateway().execute(
            NativeAlarmCommand.DeleteAll(enabledOnly = false), "test-interaction"
        )
        assertEquals(LocalActionStatus.UNSUPPORTED, unavailable.status)
        assertTrue(unavailable.message.contains("etkinleştirmen"))
    }
}
