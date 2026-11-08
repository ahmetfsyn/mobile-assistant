package com.companion.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

internal data class ConversationMessage(val role: String, val content: String)

internal data class ConversationConfiguration(
    val provider: String,
    val model: String,
    val maxHistoryMessages: Int
)

internal data class ConversationReply(val text: String)

internal class ConversationFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

internal interface ConversationEngine {
    @Throws(IOException::class)
    fun fetchConfiguration(): ConversationConfiguration

    @Throws(IOException::class)
    fun respond(
        message: String,
        language: VoiceLanguage,
        history: List<ConversationMessage>
    ): ConversationReply

    /** Interrupts an in-flight network request when the user starts a new turn or leaves. */
    fun cancelCurrentRequest()
}

internal interface ConversationHistoryStore {
    fun recentMessages(): List<ConversationMessage>
    fun appendTurn(userMessage: String, assistantMessage: String)
}

/** Stores only a bounded text history; microphone audio is never persisted. */
internal class SharedPreferencesConversationHistoryStore(context: Context) : ConversationHistoryStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun recentMessages(): List<ConversationMessage> = synchronized(lock) {
        readMessages().takeLast(MAX_HISTORY_MESSAGES)
    }

    override fun appendTurn(userMessage: String, assistantMessage: String) = synchronized(lock) {
        val updated = (readMessages() +
            ConversationMessage("user", userMessage.take(MAX_MESSAGE_CHARS)) +
            ConversationMessage("assistant", assistantMessage.take(MAX_MESSAGE_CHARS)))
            .takeLast(MAX_HISTORY_MESSAGES)
        val json = JSONArray()
        updated.forEach { message ->
            json.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        preferences.edit().putString(KEY_HISTORY, json.toString()).apply()
    }

    private fun readMessages(): List<ConversationMessage> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val role = entry.optString("role")
                    val content = entry.optString("content").trim().take(MAX_MESSAGE_CHARS)
                    if ((role == "user" || role == "assistant") && content.isNotEmpty()) {
                        add(ConversationMessage(role, content))
                    }
                }
            }.takeLast(MAX_HISTORY_MESSAGES)
        } catch (_: JSONException) {
            preferences.edit().remove(KEY_HISTORY).apply()
            emptyList()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "companion_conversation"
        const val KEY_HISTORY = "recent_messages"
        const val MAX_HISTORY_MESSAGES = 8
        const val MAX_MESSAGE_CHARS = 1_800
    }
}

/** Development fallback used only when no backend URL is configured. */
internal class MockConversationEngine : ConversationEngine {
    override fun fetchConfiguration() = ConversationConfiguration(
        provider = "mock",
        model = "local-test-response",
        maxHistoryMessages = MAX_HISTORY_MESSAGES
    )

    override fun respond(
        message: String,
        language: VoiceLanguage,
        history: List<ConversationMessage>
    ) = ConversationReply(
        text = VoiceConfig.testResponse(language)
    )

    override fun cancelCurrentRequest() = Unit

    private companion object {
        const val MAX_HISTORY_MESSAGES = 8
    }
}

/** Thin Android client for the provider-neutral HTTPS conversation API. */
internal class HttpConversationEngine(endpoint: String) : ConversationEngine {
    private val baseUrl = endpoint.trim().trimEnd('/')
    private val requestGeneration = AtomicLong()

    @Volatile private var activeConnection: HttpURLConnection? = null

    override fun fetchConfiguration(): ConversationConfiguration = fetchConfiguration(requestGeneration.get())

    private fun fetchConfiguration(token: Long): ConversationConfiguration = readConfiguration(
        request("/v1/config", "GET", null, CONFIG_READ_TIMEOUT_MS, token)
    )

    private fun readConfiguration(response: JSONObject): ConversationConfiguration {
        val provider = response.optString("provider").trim()
        val model = response.optString("model").trim()
        if (provider.isEmpty() || model.isEmpty()) {
            throw ConversationFailure("BULUT AI YAPILANDIRMASI OKUNAMADI")
        }
        return ConversationConfiguration(
            provider = provider,
            model = model,
            maxHistoryMessages = response.optInt("maxHistoryMessages", MAX_HISTORY_MESSAGES)
                .coerceIn(0, MAX_HISTORY_MESSAGES)
        )
    }

    override fun respond(
        message: String,
        language: VoiceLanguage,
        history: List<ConversationMessage>
    ): ConversationReply {
        val token = requestGeneration.get()
        val configuration = fetchConfiguration(token)
        val messages = JSONArray()
        history.takeLast(configuration.maxHistoryMessages).forEach { entry ->
            if ((entry.role == "user" || entry.role == "assistant") && entry.content.isNotBlank()) {
                messages.put(JSONObject().put("role", entry.role).put("content", entry.content))
            }
        }
        val body = JSONObject()
            .put("message", message)
            .put("language", language.tag)
            .put("history", messages)
        val response = request("/v1/conversations", "POST", body, CHAT_READ_TIMEOUT_MS, token)
        val text = response.optString("reply").trim()
        if (text.isEmpty()) throw ConversationFailure("BULUT ASİSTANI BOŞ YANIT VERDİ")
        return ConversationReply(text)
    }

    override fun cancelCurrentRequest() {
        requestGeneration.incrementAndGet()
        activeConnection?.disconnect()
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject?,
        readTimeoutMs: Int,
        token: Long
    ): JSONObject {
        if (requestGeneration.get() != token) throw ConversationFailure("KONUŞMA İPTAL EDİLDİ")
        val url = try {
            URL("$baseUrl$path")
        } catch (error: MalformedURLException) {
            throw ConversationFailure("BULUT SUNUCU ADRESİ GEÇERSİZ", error)
        }
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw ConversationFailure("BULUT SUNUCU ADRESİ HTTPS OLMALI")
        }
        if (url.userInfo != null || url.query != null || url.ref != null) {
            throw ConversationFailure("BULUT SUNUCU ADRESİ KİMLİK BİLGİSİ İÇERMEMELİ")
        }

        val connection = try {
            url.openConnection() as? HttpURLConnection
                ?: throw ConversationFailure("BULUT SUNUCUSUNA BAĞLANILAMADI")
        } catch (error: ConversationFailure) {
            throw error
        } catch (error: Exception) {
            throw ConversationFailure("BULUT SUNUCUSUNA BAĞLANILAMADI", error)
        }
        activeConnection = connection
        try {
            if (requestGeneration.get() != token) throw ConversationFailure("KONUŞMA İPTAL EDİLDİ")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(1024)
                var count = reader.read(buffer)
                while (count >= 0) {
                    if (result.length + count > MAX_RESPONSE_CHARS) {
                        throw ConversationFailure("BULUT SUNUCUSU YANITI ÇOK UZUN")
                    }
                    result.append(buffer, 0, count)
                    count = reader.read(buffer)
                }
                result.toString()
            }.orEmpty()
            if (code !in 200..299) throw serverFailure(code, responseText)
            return JSONObject(responseText)
        } catch (error: SocketTimeoutException) {
            throw ConversationFailure("YANIT ZAMAN AŞIMINA UĞRADI")
        } catch (error: UnknownHostException) {
            throw ConversationFailure("İNTERNET BAĞLANTISI YOK")
        } catch (error: JSONException) {
            throw ConversationFailure("BULUT SUNUCUSU YANITI OKUNAMADI", error)
        } catch (error: ConversationFailure) {
            throw error
        } catch (error: IOException) {
            throw ConversationFailure("BULUT ASİSTANINA ULAŞILAMADI", error)
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun serverFailure(code: Int, body: String): ConversationFailure {
        val errorCode = try {
            JSONObject(body).optJSONObject("error")?.optString("code").orEmpty()
        } catch (_: JSONException) {
            ""
        }
        val message = when {
            errorCode == "quota_exceeded" -> "AI KOTASI TÜKENDİ; SINIRLAR SIFIRLANINCA YENİDEN DENE"
            code == 429 || errorCode == "rate_limited" -> "AI SAĞLAYICISI ŞU ANDA YOĞUN"
            errorCode == "missing_provider_credentials" -> "BULUT AI KİMLİK BİLGİLERİ YAPILANDIRILMAMIŞ"
            errorCode == "invalid_provider_credentials" -> "BULUT AI KİMLİK BİLGİLERİ GEÇERSİZ"
            errorCode == "unsupported_model" -> "SEÇİLEN AI MODELİ DESTEKLENMİYOR"
            errorCode == "unsupported_provider" -> "AI SAĞLAYICISI DESTEKLENMİYOR"
            code == 503 -> "BULUT ASİSTANI HAZIR DEĞİL"
            code in 400..499 -> "BULUT İSTEĞİ KABUL EDİLMEDİ"
            else -> "BULUT ASİSTANINA ŞU ANDA ULAŞILAMIYOR"
        }
        return ConversationFailure(message)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 7_000
        const val CONFIG_READ_TIMEOUT_MS = 5_000
        const val CHAT_READ_TIMEOUT_MS = 25_000
        const val MAX_RESPONSE_CHARS = 64 * 1024
        const val MAX_HISTORY_MESSAGES = 8
    }
}

internal object ConversationEngineFactory {
    fun create(endpoint: String): ConversationEngine {
        if (endpoint.isBlank()) {
            Log.i("VoiceInteraction", "Bulut AI adresi ayarlı değil; yerel deneme yanıtı kullanılacak.")
            return MockConversationEngine()
        }
        return HttpConversationEngine(endpoint)
    }
}
