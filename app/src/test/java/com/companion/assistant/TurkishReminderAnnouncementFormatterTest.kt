package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class TurkishReminderAnnouncementFormatterTest {
    @Test
    fun turnsStoredInfinitiveAndNominalizedTextIntoCompleteAnnouncementsWithoutMutatingIt() {
        assertAnnouncement("Su içmeyi", "Su içme zamanı! Su içmeyi unutma.")
        assertAnnouncement("Kitap okumak", "Kitap okuma zamanı! Kitap okumayı unutma.")
    }

    @Test
    fun handlesCommonImperativesAndKeepsTheOriginalAction() {
        assertAnnouncement("İlaçlarımı al", "İlaçlarını almayı unutma.")
        assertAnnouncement("Kitabımı oku", "Kitabını okuma zamanı! Kitabını okumayı unutma.")
        assertAnnouncement("Sütümü iç", "Sütünü içme zamanı! Sütünü içmeyi unutma.")
        assertAnnouncement("Dışarı çık", "Dışarı çıkma zamanı! Dışarı çıkmayı unutma.")
        assertAnnouncement("Egzersiz yap", "Egzersiz yapma zamanı! Egzersiz yapmayı unutma.")
        assertAnnouncement("Annemi ara", "Annene telefon etmeyi unutma.")
        assertAnnouncement("Marketten süt al", "Marketten süt almayı unutma.")
        assertAnnouncement("Toplantıya katıl", "Toplantıya katılmayı unutma.")
    }

    @Test
    fun formatsFirstPersonNominalizedActionsUsedInReminderRequests() {
        assertAnnouncement("Egzersiz yapmamı", "Egzersiz yapma zamanı! Egzersiz yapmayı unutma.")
        assertAnnouncement("Annemi aramamı", "Annene telefon etmeyi unutma.")
    }

    @Test
    fun formatsTheThreePhysicalTestCommandsAfterTheRealParserExtractsTheirText() {
        val cases = listOf(
            "2 dakika sonra su içmeyi hatırlat." to "Su içme zamanı! Su içmeyi unutma.",
            "2 dakika sonra egzersiz yapmayı hatırlat." to "Egzersiz yapma zamanı! Egzersiz yapmayı unutma.",
            "2 dakika sonra annemi aramamı hatırlat." to "Annene telefon etmeyi unutma."
        )
        val now = 1_800_000_000_000L

        cases.forEach { (request, expectedSpeech) ->
            val command = TurkishReminderCommandParser.parse(request, now, "Europe/Istanbul")
            assertEquals(true, command is ReminderCommand.Create)
            command as ReminderCommand.Create
            assertEquals(expectedSpeech, TurkishReminderAnnouncementFormatter.format(command.message))
        }
    }

    @Test
    fun handlesTaskLabelsTurkishCaseAndRecurringReminders() {
        assertAnnouncement("Market alışverişi", "Market alışverişi zamanı!")
        assertAnnouncement("su içmeyi", "Her gün, su içme zamanı! Su içmeyi unutma.", recurrence = "daily")
        assertAnnouncement("raporu gönder", "Raporu göndermeyi unutma.")
    }

    @Test
    fun malformedAndAmbiguousTextUseSafeFallbacks() {
        assertEquals("Hatırlatma zamanı.", TurkishReminderAnnouncementFormatter.format("   "))
        assertEquals("Hatırlatma zamanı.", TurkishReminderAnnouncementFormatter.format("..."))
        assertEquals(
            "Hatırlatma: Belki toplantı ya da bir şey.",
            TurkishReminderAnnouncementFormatter.format("Belki toplantı ya da bir şey")
        )
        assertEquals(
            "Hatırlatma: Saat 10'da toplantı mı.",
            TurkishReminderAnnouncementFormatter.format("Saat 10'da toplantı mı?")
        )
    }

    @Test
    fun everyDeliveryStateAndNotificationUsesTheSameAnnouncementAndKeepsStoredText() {
        val source = record("İlaçlarımı al", "none")
        val expected = "İlaçlarını almayı unutma."
        val states = listOf(
            source,
            source.copy(deliveryState = "deferred", pendingAtMillis = source.nextAtMillis),
            source.copy(status = "needs_attention", deliveryState = "failed"),
            source.copy(status = "delivered", deliveryState = "done")
        )

        states.forEach { reminder ->
            assertEquals(expected, reminder.announcement())
            assertEquals(expected, ReminderNotificationFormatter.format(reminder).body)
            assertEquals(expected, ReminderNotificationFormatter.format(reminder, ReminderNotificationKind.FAILED).body)
            assertEquals("İlaçlarımı al", reminder.message)
        }
    }

    private fun assertAnnouncement(source: String, expected: String, recurrence: String = "none") {
        val reminder = record(source, recurrence)
        assertEquals(expected, reminder.announcement())
        assertEquals(source, reminder.message)
    }

    private fun record(message: String, recurrence: String) = ReminderRecord(
        id = "test-id",
        message = message,
        scheduledAtMillis = 1_800_000_000_000L,
        timeZoneId = "Europe/Istanbul",
        recurrence = recurrence,
        status = "scheduled",
        createdAtMillis = 1_800_000_000_000L,
        updatedAtMillis = 1_800_000_000_000L,
        lastDeliveredAtMillis = null,
        nextAtMillis = 1_800_000_000_000L,
        pendingAtMillis = null,
        attempts = 0,
        deliveryState = "idle"
    )
}
