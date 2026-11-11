package com.companion.assistant

import android.app.Instrumentation
import android.os.Bundle
import java.util.UUID

/** ADB-only probe that exercises the same conversation client used by the face. */
class BackendProbeInstrumentation : Instrumentation() {
    private var runConversation = false
    private var conversationBatch = false
    private var securityStoreProbe = false
    private var reminderStoreProbe = false
    private var endpointOverride: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runConversation = arguments?.getString("conversation") == "true"
        conversationBatch = arguments?.getString("conversationBatch") == "true"
        securityStoreProbe = arguments?.getString("securityStore") == "true"
        reminderStoreProbe = arguments?.getString("reminderStore") == "true"
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

            val endpoint = endpointOverride ?: BuildConfig.COMPANION_BACKEND_URL
            check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
            val engine = ConversationEngineFactory.create(endpoint)
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
}
