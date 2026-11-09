package com.companion.assistant

import android.app.Instrumentation
import android.os.Bundle
import java.util.UUID

/** ADB-only probe that exercises the same conversation client used by the face. */
class BackendProbeInstrumentation : Instrumentation() {
    private var runConversation = false
    private var conversationBatch = false
    private var endpointOverride: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runConversation = arguments?.getString("conversation") == "true"
        conversationBatch = arguments?.getString("conversationBatch") == "true"
        endpointOverride = arguments?.getString("endpoint")?.trim()?.takeIf(String::isNotEmpty)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var stage = "configuration"
        try {
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
}
