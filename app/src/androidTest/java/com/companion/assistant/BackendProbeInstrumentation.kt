package com.companion.assistant

import android.app.Instrumentation
import android.app.NotificationManager
import android.os.Bundle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** ADB-only probe that exercises the same conversation client used by the face. */
class BackendProbeInstrumentation : Instrumentation() {
    private var runConversation = false
    private var conversationBatch = false
    private var securityStoreProbe = false
    private var reminderStoreProbe = false
    private var reminderSettingsProbe = false
    private var backendHealthProbe = false
    private var backendReminderSyncProbe = false
    private var backendPhase6RoundTripProbe = false
    private var persistencePrepareProbe = false
    private var persistenceVerifyProbe = false
    private var endpointOverride: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runConversation = arguments?.getString("conversation") == "true"
        conversationBatch = arguments?.getString("conversationBatch") == "true"
        securityStoreProbe = arguments?.getString("securityStore") == "true"
        reminderStoreProbe = arguments?.getString("reminderStore") == "true"
        reminderSettingsProbe = arguments?.getString("reminderSettings") == "true"
        backendHealthProbe = arguments?.getString("backendHealth") == "true"
        backendReminderSyncProbe = arguments?.getString("backendReminderSync") == "true"
        backendPhase6RoundTripProbe = arguments?.getString("backendPhase6RoundTrip") == "true"
        persistencePrepareProbe = arguments?.getString("persistencePrepare") == "true"
        persistenceVerifyProbe = arguments?.getString("persistenceVerify") == "true"
        endpointOverride = arguments?.getString("endpoint")?.trim()?.takeIf(String::isNotEmpty)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var stage = "configuration"
        try {
            if (securityStoreProbe) {
                stage = "security_store"
                runSecurityStoreProbe()
                results.putString("securityStore", "ok")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            if (reminderStoreProbe) {
                stage = "reminder_store"
                runReminderStoreProbe()
                results.putString("reminderStore", "ok")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            if (reminderSettingsProbe) {
                stage = "reminder_settings"
                val settings = ReminderSettingsStore(targetContext).get()
                check(settings.autonomyEnabled) { "Reminder autonomy is disabled." }
                check(settings.proactiveSpeechEnabled) { "Proactive speech is disabled." }
                check(!settings.quietHoursEnabled) { "Quiet hours are enabled." }
                results.putString("autonomyEnabled", settings.autonomyEnabled.toString())
                results.putString("proactiveSpeechEnabled", settings.proactiveSpeechEnabled.toString())
                results.putString("quietHoursEnabled", settings.quietHoursEnabled.toString())
                results.putString("reminderSettings", "ok")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            if (backendHealthProbe) {
                stage = "backend_health"
                val endpoint = endpointOverride ?: BuildConfig.COMPANION_BACKEND_URL
                check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
                val connection = URL("${endpoint.trimEnd('/')}/healthz").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                val status = try { connection.responseCode } finally { connection.disconnect() }
                check(status == 200) { "Backend health returned HTTP $status." }
                results.putInt("healthStatus", status)
                results.putString("backendHealth", "ok")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            if (backendPhase6RoundTripProbe) {
                stage = "backend_phase6_round_trip"
                runBackendPhase6RoundTrip(endpointOverride ?: BuildConfig.COMPANION_BACKEND_URL)
                results.putString("backendPhase6RoundTrip", "ok")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            if (persistencePrepareProbe) {
                stage = "backend_persistence_prepare"
                val reminderId = prepareBackendPersistenceProbe(endpointOverride ?: BuildConfig.COMPANION_BACKEND_URL)
                results.putString("reminderId", reminderId)
                results.putString("persistencePrepare", "ok")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            if (persistenceVerifyProbe) {
                stage = "backend_persistence_verify"
                val syncedInBackground = verifyBackendPersistenceProbe(endpointOverride ?: BuildConfig.COMPANION_BACKEND_URL)
                results.putString("persistenceVerify", "ok")
                results.putString("backgroundDeliverySync", if (syncedInBackground) "ok" else "reconciled_after_backend_return")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            if (backendReminderSyncProbe) {
                stage = "backend_reminder_sync"
                val endpoint = endpointOverride ?: BuildConfig.COMPANION_BACKEND_URL
                check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
                val tokenStore = CompanionAccessTokenStore(targetContext)
                val settings = ReminderSettingsStore(targetContext).get()
                ReminderApiClient(endpoint, tokenStore::accessToken).syncSettings(settings)
                results.putString("backendReminderSync", "ok")
                results.putString("result", "ok")
                finish(0, results)
                return
            }

            val endpoint = endpointOverride ?: BuildConfig.COMPANION_BACKEND_URL
            check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
            val tokenStore = CompanionAccessTokenStore(targetContext)
            val consentStore = MemoryConsentStore(targetContext)
            val engine = ConversationEngineFactory.create(
                endpoint = endpoint,
                accessTokenProvider = tokenStore::accessToken,
                memoryConsentProvider = consentStore::isEnabled
            )
            val configuration = engine.fetchConfiguration()
            results.putString("provider", configuration.provider)
            results.putString("model", configuration.model)

            if (runConversation || conversationBatch) {
                stage = "conversation"
                val inputs = if (conversationBatch) {
                    listOf(
                        "Türkiye'nin başkenti neresidir?",
                        "Bana bir uzay hikayesi anlat.",
                        "En sevdiğin yemek nedir?"
                    )
                } else {
                    listOf("Bir cümlelik kısa bir bağlantı testi yanıtı ver.")
                }
                val replies = inputs.map { message ->
                    val reply = engine.respond(
                        message,
                        VoiceLanguage.TURKISH,
                        emptyList(),
                        UUID.randomUUID().toString()
                    )
                    check(reply.text.isNotBlank()) { "Conversation reply was empty." }
                    reply.text
                }
                results.putStringArray("responses", replies.toTypedArray())
                results.putInt("conversationCount", replies.size)
                results.putString("conversation", "ok")
            }
            results.putString("result", "ok")
            finish(0, results)
        } catch (error: Exception) {
            results.putString("result", "failed")
            results.putString("stage", stage)
            results.putString("errorClass", error.javaClass.simpleName)
            results.putString("errorMessage", error.message.orEmpty())
            results.putString("causeClass", error.cause?.javaClass?.simpleName.orEmpty())
            finish(1, results)
        }
    }

    private fun runSecurityStoreProbe() {
        val tokenStore = CompanionAccessTokenStore(targetContext)
        val consentStore = MemoryConsentStore(targetContext)
        val previousToken = tokenStore.accessToken()
        val previousConsent = consentStore.isEnabled()
        try {
            check(!tokenStore.saveAccessToken("too-short")) { "A short access token was accepted." }
            val syntheticToken = "synthetic-device-token-for-keystore-probe-2026"
            check(tokenStore.saveAccessToken(syntheticToken)) { "Android Keystore could not encrypt the test token." }
            check(tokenStore.accessToken() == syntheticToken) { "Android Keystore could not decrypt the test token." }
            tokenStore.clearAccessToken()
            check(tokenStore.accessToken().isEmpty()) { "Cleared token remained available." }

            consentStore.setEnabled(!previousConsent)
            check(consentStore.isEnabled() == !previousConsent) { "Memory consent preference did not persist." }
        } finally {
            if (previousToken.isEmpty()) tokenStore.clearAccessToken()
            else check(tokenStore.saveAccessToken(previousToken)) { "Could not restore the existing access token." }
            consentStore.setEnabled(previousConsent)
        }
    }

    private fun runReminderStoreProbe() {
        val store = ReminderStore(targetContext)
        var reminderId: String? = null
        try {
            val created = store.create("Instrumentasyon geçici hatırlatması", System.currentTimeMillis() + 60_000L, "none")
            reminderId = created.id
            check(store.get(created.id)?.status == "scheduled") { "Reminder was not persisted." }
            val updated = store.update(created.id, "Düzenlenmiş geçici hatırlatma", System.currentTimeMillis() + 120_000L, "daily")
            check(updated?.recurrence == "daily") { "Reminder update was not persisted." }
            check(store.pause(created.id)?.status == "paused") { "Reminder did not pause." }
            check(store.resume(created.id)?.status == "scheduled") { "Reminder did not resume." }
            check(store.complete(created.id)?.status == "completed") { "Reminder did not complete." }
        } finally {
            reminderId?.let(store::delete)
            store.close()
        }
    }

    private fun runBackendPhase6RoundTrip(endpoint: String) {
        check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
        val tokenStore = CompanionAccessTokenStore(targetContext)
        val token = tokenStore.accessToken()
        check(token.isNotBlank()) { "Companion access token is unavailable on the device." }
        val api = ReminderApiClient(endpoint, tokenStore::accessToken)
        val settingsStore = ReminderSettingsStore(targetContext)
        val originalSettings = settingsStore.get()
        val changedSettings = originalSettings.copy(quietHoursEnabled = !originalSettings.quietHoursEnabled)
        val store = ReminderStore(targetContext)
        val scheduler = ReminderScheduler(targetContext, store)
        var reminder: ReminderRecord? = null
        var remoteMayContainReminder = false
        try {
            settingsStore.save(changedSettings)
            api.syncSettings(changedSettings)
            assertSettings(backendRequest(endpoint, token, "GET", "/v1/autonomy").getJSONObject("settings"), changedSettings)

            val created = store.create("Geçici cihaz senkronizasyon denemesi", System.currentTimeMillis() + 10 * 60_000L, "none")
            reminder = created
            scheduler.schedule(created)
            remoteMayContainReminder = true
            api.create(created)
            assertReminder(endpoint, token, created.id, "scheduled")

            scheduler.cancel(created)
            val paused = store.pause(created.id) ?: error("The local test reminder could not be paused.")
            api.update(paused)
            assertReminder(endpoint, token, created.id, "paused")

            val resumed = store.resume(created.id) ?: error("The local test reminder could not be resumed.")
            scheduler.schedule(resumed)
            api.update(resumed)
            assertReminder(endpoint, token, created.id, "scheduled")

            scheduler.cancel(resumed)
            val completed = store.complete(created.id) ?: error("The local test reminder could not be completed.")
            api.update(completed)
            assertReminder(endpoint, token, created.id, "completed")

            api.delete(created.id)
            remoteMayContainReminder = false
            check(!remoteReminderExists(endpoint, token, created.id)) { "The backend did not remove the test reminder." }
        } finally {
            reminder?.let { testReminder ->
                scheduler.cancel(testReminder)
                if (store.get(testReminder.id) != null) store.delete(testReminder.id)
                if (remoteMayContainReminder) runCatching { api.delete(testReminder.id) }
            }
            settingsStore.save(originalSettings)
            api.syncSettings(originalSettings)
            assertSettings(backendRequest(endpoint, token, "GET", "/v1/autonomy").getJSONObject("settings"), originalSettings)
            store.close()
        }
    }

    /** Creates a local Android alarm and the matching backend reminder before the caller restarts Docker. */
    private fun prepareBackendPersistenceProbe(endpoint: String): String {
        check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
        val tokenStore = CompanionAccessTokenStore(targetContext)
        val token = tokenStore.accessToken()
        check(token.isNotBlank()) { "Companion access token is unavailable on the device." }
        val api = ReminderApiClient(endpoint, tokenStore::accessToken)
        val settings = ReminderSettingsStore(targetContext).get()
        check(settings.autonomyEnabled) { "Autonomy must stay enabled for the local alarm test." }
        api.syncSettings(settings)

        val store = ReminderStore(targetContext)
        val scheduler = ReminderScheduler(targetContext, store)
        var reminder: ReminderRecord? = null
        var remoteMayContainReminder = false
        try {
            val created = store.create("Faz 6 yerel alarm doğrulaması", System.currentTimeMillis() + 45_000L, "none")
            reminder = created
            scheduler.schedule(created)
            remoteMayContainReminder = true
            api.create(created)
            targetContext.getSharedPreferences(PHASE6_PROBE_PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_PERSISTENCE_REMINDER_ID, created.id).commit()
            return created.id
        } catch (error: Exception) {
            reminder?.let { testReminder ->
                scheduler.cancel(testReminder)
                if (store.get(testReminder.id) != null) store.delete(testReminder.id)
                if (remoteMayContainReminder) runCatching { api.delete(testReminder.id) }
            }
            throw error
        } finally {
            store.close()
        }
    }

    /** Confirms Docker-volume persistence and local delivery while the API container is stopped. */
    private fun verifyBackendPersistenceProbe(endpoint: String): Boolean {
        check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
        val preferences = targetContext.getSharedPreferences(PHASE6_PROBE_PREFERENCES, android.content.Context.MODE_PRIVATE)
        val reminderId = preferences.getString(KEY_PERSISTENCE_REMINDER_ID, null)
            ?: error("The device persistence test reminder ID is missing.")
        val tokenStore = CompanionAccessTokenStore(targetContext)
        val token = tokenStore.accessToken()
        check(token.isNotBlank()) { "Companion access token is unavailable on the device." }
        val api = ReminderApiClient(endpoint, tokenStore::accessToken)
        val settings = ReminderSettingsStore(targetContext).get()
        val store = ReminderStore(targetContext)
        val scheduler = ReminderScheduler(targetContext, store)
        val localReminder = store.get(reminderId) ?: error("The local persistence test reminder is missing.")
        try {
            check(localReminder.status == "delivered" && localReminder.lastDeliveredAtMillis != null) {
                "The Android alarm did not mark the local reminder delivered while the backend was stopped."
            }
            val activeNotifications = (targetContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications
            check(activeNotifications.any { it.tag == "reminder:$reminderId" }) {
                "The local reminder notification was not present on the device."
            }
            assertSettings(backendRequest(endpoint, token, "GET", "/v1/autonomy").getJSONObject("settings"), settings)
            val backgroundSyncSucceeded = when (remoteReminderStatus(endpoint, token, reminderId)) {
                "completed" -> true
                "scheduled" -> {
                    // Local delivery is authoritative; reconcile the mirror after an outage.
                    api.update(localReminder)
                    false
                }
                else -> error("The backend returned an unexpected status for the delivered reminder.")
            }
            assertReminder(endpoint, token, reminderId, "completed")
            api.delete(reminderId)
            check(!remoteReminderExists(endpoint, token, reminderId)) { "The backend test reminder could not be deleted." }
            ReminderNotifications.cancel(targetContext, reminderId)
            check(store.delete(reminderId)) { "The local test reminder could not be deleted." }
            preferences.edit().remove(KEY_PERSISTENCE_REMINDER_ID).commit()
            return backgroundSyncSucceeded
        } finally {
            localReminder?.let(scheduler::cancel)
            if (store.get(reminderId) != null) store.delete(reminderId)
            if (preferences.getString(KEY_PERSISTENCE_REMINDER_ID, null) != null) {
                runCatching { api.delete(reminderId) }
                preferences.edit().remove(KEY_PERSISTENCE_REMINDER_ID).commit()
                ReminderNotifications.cancel(targetContext, reminderId)
            }
            store.close()
        }
    }

    private fun assertSettings(actual: JSONObject, expected: ReminderSettings) {
        check(actual.getBoolean("autonomyEnabled") == expected.autonomyEnabled) { "Backend autonomy setting did not match the device." }
        check(actual.getBoolean("proactiveSpeechEnabled") == expected.proactiveSpeechEnabled) { "Backend proactive speech setting did not match the device." }
        check(actual.getBoolean("quietHoursEnabled") == expected.quietHoursEnabled) { "Backend quiet-hours setting did not match the device." }
        check(actual.getString("quietHoursStart") == expected.quietHoursStart) { "Backend quiet-hours start did not match the device." }
        check(actual.getString("quietHoursEnd") == expected.quietHoursEnd) { "Backend quiet-hours end did not match the device." }
    }

    private fun assertReminder(endpoint: String, token: String, id: String, expectedStatus: String) {
        val status = remoteReminderStatus(endpoint, token, id)
        check(status == expectedStatus) { "The backend reminder status did not match $expectedStatus." }
    }

    private fun remoteReminderStatus(endpoint: String, token: String, id: String): String {
        val reminders = backendRequest(endpoint, token, "GET", "/v1/reminders").getJSONArray("reminders")
        val reminder = (0 until reminders.length()).map { reminders.getJSONObject(it) }
            .firstOrNull { it.optString("id") == id }
            ?: error("The backend did not return the device test reminder.")
        return reminder.getString("status")
    }

    private fun remoteReminderExists(endpoint: String, token: String, id: String): Boolean {
        val reminders = backendRequest(endpoint, token, "GET", "/v1/reminders").getJSONArray("reminders")
        return (0 until reminders.length()).any { reminders.getJSONObject(it).optString("id") == id }
    }

    private fun backendRequest(endpoint: String, token: String, method: String, path: String): JSONObject {
        val connection = (URL(endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "Backend $method request failed with HTTP $status." }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PHASE6_PROBE_PREFERENCES = "phase6_backend_probe"
        const val KEY_PERSISTENCE_REMINDER_ID = "persistence_reminder_id"
    }
}
