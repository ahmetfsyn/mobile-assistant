package com.companion.assistant

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSyncAndSpeechTest {
    @Test
    fun offlineOutboxSurvivesDispatcherRestartAndReplaysOperationsInOrder() {
        val queue = MemoryOutbox().apply {
            add("upsert", "reminder-a", "reminder-a|created")
        }
        val api = FakeReminderApi().apply { online = false }
        try {
            ReminderSyncDispatcher(queue, api).flush()
            throw AssertionError("Offline request should fail")
        } catch (_: IOException) {
            assertEquals(1, queue.items.size)
            assertTrue(api.records.isEmpty())
        }

        // The same durable queue is read by a newly constructed dispatcher after process restart.
        queue.add("upsert", "reminder-a", "reminder-a|updated")
        queue.add("upsert", "reminder-a", "reminder-a|paused")
        queue.add("upsert", "reminder-a", "reminder-a|resumed")
        queue.add("delete", "reminder-a", null)
        api.online = true

        assertEquals(5, ReminderSyncDispatcher(queue, api).flush())
        assertTrue(queue.items.isEmpty())
        assertTrue(api.records.isEmpty())
        assertEquals(
            listOf("upsert:reminder-a:created", "upsert:reminder-a:updated", "upsert:reminder-a:paused",
                "upsert:reminder-a:resumed", "delete:reminder-a"),
            api.operations
        )
        assertEquals(1, api.deleteCount)
    }

    @Test
    fun ambiguousSuccessfulRequestCanRetryWithoutCreatingDuplicateServerRecords() {
        val queue = MemoryOutbox().apply { add("upsert", "same-id", "same-id|created") }
        val api = FakeReminderApi().apply { failAfterFirstWrite = true }
        try {
            ReminderSyncDispatcher(queue, api).flush()
            throw AssertionError("Lost response should keep the outbox entry")
        } catch (_: IOException) {
            assertEquals(1, queue.items.size)
            assertEquals(1, api.records.size)
        }
        assertEquals(1, ReminderSyncDispatcher(queue, api).flush())
        assertEquals(1, api.records.size)
        assertTrue(queue.items.isEmpty())
        assertEquals(2, api.upsertCount)
    }

    @Test
    fun proactiveSpeechRequiresEveryConsentAndIdleGateAndUsesTheSpeechRequest() {
        val zone = java.util.TimeZone.getTimeZone("Europe/Istanbul")
        val now = java.util.Calendar.getInstance(zone).apply {
            set(2030, java.util.Calendar.JANUARY, 2, 2, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val settings = ReminderSettings(
            autonomyEnabled = true,
            proactiveSpeechEnabled = true,
            quietHoursEnabled = false
        )
        var requests = 0
        var spokenText: String? = null
        val flow = ProactiveSpeechFlow { text, callback ->
            requests++
            spokenText = text
            callback(true)
        }
        val allowed = ProactiveSpeechGate.blockedReason(
            settings, now, "Europe/Istanbul", RobotActivityState.IDLE, now
        ) == null
        assertTrue(flow.request("Proaktif ses testi.", allowed) { assertTrue(it) })
        assertEquals(1, requests)
        assertEquals("Proaktif ses testi.", spokenText)

        val activeListening = ProactiveSpeechGate.blockedReason(
            settings, now, "Europe/Istanbul", RobotActivityState.LISTENING, now
        )
        assertEquals("robot meşgul", activeListening)
        assertFalse(flow.request("Bu sırada konuşma.", activeListening == null) { assertFalse(it) })

        assertEquals("otonomi kapalı", ProactiveSpeechGate.blockedReason(
            settings.copy(autonomyEnabled = false), now, "Europe/Istanbul", RobotActivityState.IDLE, now
        ))
        assertEquals("sesli hatırlatma izni kapalı", ProactiveSpeechGate.blockedReason(
            settings.copy(proactiveSpeechEnabled = false), now, "Europe/Istanbul", RobotActivityState.IDLE, now
        ))
        assertEquals("sessiz saatler etkin", ProactiveSpeechGate.blockedReason(
            settings.copy(quietHoursEnabled = true),
            now, "Europe/Istanbul", RobotActivityState.IDLE, now
        ))
        assertEquals("konuşma bekleme süresi dolmadı", ProactiveSpeechGate.blockedReason(
            settings, now, "Europe/Istanbul", RobotActivityState.IDLE, now + 1
        ))
        assertEquals(1, requests)
    }

    @Test
    fun api25SelectsLegacyTtsUsageAndApi26UsesAssistantUsage() {
        assertEquals(1, TtsAudioUsage.forSdk(25))
        assertEquals(16, TtsAudioUsage.forSdk(26))
    }

    private class MemoryOutbox : ReminderSyncQueue {
        val items = mutableListOf<ReminderSyncOperation>()
        private var sequence = 1L

        fun add(operation: String, id: String, payload: String?) {
            items += ReminderSyncOperation(sequence++, operation, id, payload)
        }

        override fun pendingSyncOperations(limit: Int): List<ReminderSyncOperation> = items.take(limit)

        override fun acknowledgeSyncOperation(sequence: Long): Boolean = items.removeAll { it.sequence == sequence }
    }

    private class FakeReminderApi : ReminderSyncApi {
        var online = true
        var failAfterFirstWrite = false
        var upsertCount = 0
        var deleteCount = 0
        val records = linkedMapOf<String, String>()
        val operations = mutableListOf<String>()

        override fun syncSnapshot(payload: String) {
            if (!online) throw IOException("offline")
            val pieces = payload.split('|', limit = 2)
            val id = pieces[0]
            val value = pieces.getOrElse(1) { "" }
            upsertCount++
            records[id] = value
            operations += "upsert:$id:$value"
            if (failAfterFirstWrite) {
                failAfterFirstWrite = false
                throw IOException("response lost")
            }
        }

        override fun delete(id: String) {
            if (!online) throw IOException("offline")
            deleteCount++
            records.remove(id)
            operations += "delete:$id"
        }
    }
}
