package com.companion.assistant

import android.app.Instrumentation
import android.os.Bundle

/** ADB-only probe that exercises the same conversation client used by the face. */
class BackendProbeInstrumentation : Instrumentation() {
    private var runConversation = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runConversation = arguments?.getString("conversation") == "true"
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var stage = "configuration"
        try {
            val endpoint = BuildConfig.COMPANION_BACKEND_URL
            check(endpoint.isNotBlank()) { "Backend URL is empty in this APK." }
            val engine = ConversationEngineFactory.create(endpoint)
            val configuration = engine.fetchConfiguration()
            results.putString("provider", configuration.provider)
            results.putString("model", configuration.model)

            if (runConversation) {
                stage = "conversation"
                val reply = engine.respond(
                    "Bir cümlelik kısa bir bağlantı testi yanıtı ver.",
                    VoiceLanguage.TURKISH,
                    emptyList()
                )
                check(reply.text.isNotBlank()) { "Conversation reply was empty." }
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
