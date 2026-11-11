package com.companion.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.ConnectException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class ConversationMessage(val role: String, val content: String)

internal data class ConversationConfiguration(
    val provider: String,
    val model: String,
    val maxHistoryMessages: Int
)

internal data class ConversationReply(val text: String)

internal class ConversationFailure(
    message: String,
    cause: Throwable? = null,
    val code: String? = null
) : IOException(message, cause)

internal interface ConversationEngine {
    @Throws(IOException::class)
    fun fetchConfiguration(): ConversationConfiguration

    @Throws(IOException::class)
    fun respond(
        message: String,
        language: VoiceLanguage,
        history: List<ConversationMessage>,
        interactionId: String
    ): ConversationReply

    /** Interrupts an in-flight network request when the user starts a new turn or leaves. */
    fun cancelCurrentRequest()
}

internal interface ConversationHistoryStore {
    fun recentMessages(): List<ConversationMessage>
    fun appendTurn(userMessage: String, assistantMessage: String)
}

internal data class EncodedConversationBody(
    val message: String,
    val languageTag: String,
    val history: List<ConversationMessage>,
    val memoryEnabled: Boolean
) {
    fun toJsonObject(): JSONObject {
        val messages = JSONArray()
        history.forEach { entry ->
            if ((entry.role == "user" || entry.role == "assistant") && entry.content.isNotBlank()) {
                messages.put(JSONObject().put("role", entry.role).put("content", entry.content))
            }
        }
        return JSONObject()
            .put("message", message)
            .put("language", languageTag)
            .put("history", messages)
            .put("memoryEnabled", memoryEnabled)
    }
}

internal fun encodeConversationBody(
    message: String,
    language: VoiceLanguage,
    history: List<ConversationMessage>,
    memoryEnabled: Boolean
): EncodedConversationBody {
    val filteredHistory = history.filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
    return EncodedConversationBody(message, language.tag, filteredHistory, memoryEnabled)
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

/** Static development response, available only through the factory's explicit useMock opt-in. */
internal class MockConversationEngine : ConversationEngine {
    override fun fetchConfiguration() = ConversationConfiguration(
        provider = "mock",
        model = "local-test-response",
        maxHistoryMessages = MAX_HISTORY_MESSAGES
    )

    override fun respond(
        message: String,
        language: VoiceLanguage,
        history: List<ConversationMessage>,
        interactionId: String
    ): ConversationReply {
        if (message.isBlank()) throw ConversationFailure("KONUŞMA METNİ BOŞ")
        return ConversationReply(VoiceConfig.testResponse(language))
    }

    override fun cancelCurrentRequest() = Unit

    private companion object {
        const val MAX_HISTORY_MESSAGES = 8
    }
}

/** Explicit error path used when no backend URL is configured; it never invents a reply. */
internal class UnconfiguredConversationEngine : ConversationEngine {
    override fun fetchConfiguration(): ConversationConfiguration =
        throw ConversationFailure("BULUT AI ADRESİ YAPILANDIRILMAMIŞ")

    override fun respond(
        message: String,
        language: VoiceLanguage,
        history: List<ConversationMessage>,
        interactionId: String
    ): ConversationReply {
        Log.e(TAG, "conversation_request_not_sent interactionId=$interactionId reason=backend_url_missing")
        throw ConversationFailure("BULUT AI ADRESİ YAPILANDIRILMAMIŞ")
    }

    override fun cancelCurrentRequest() = Unit

    private companion object {
        const val TAG = "VoiceInteraction"
    }
}

/** Thin Android client for the provider-neutral conversation API. */
internal class HttpConversationEngine(
    endpoint: String,
    private val accessTokenProvider: () -> String = { "" },
    private val memoryConsentProvider: () -> Boolean = { false }
) : ConversationEngine {
    private val baseUrl = endpoint.trim().trimEnd('/')
    private val requestGeneration = AtomicLong()

    @Volatile private var activeConnection: HttpURLConnection? = null

    override fun fetchConfiguration(): ConversationConfiguration = fetchConfiguration(
        requestGeneration.get(),
        UUID.randomUUID().toString()
    )

    private fun fetchConfiguration(token: Long, interactionId: String): ConversationConfiguration = readConfiguration(
        request("/v1/config", "GET", null, CONFIG_READ_TIMEOUT_MS, token, interactionId)
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
        history: List<ConversationMessage>,
        interactionId: String
    ): ConversationReply {
        val currentMessage = message.trim()
        if (currentMessage.isEmpty()) throw ConversationFailure("KONUŞMA METNİ BOŞ")
        if (!UUID_PATTERN.matches(interactionId)) throw ConversationFailure("ETKİLEŞİM KİMLİĞİ GEÇERSİZ")
        val token = requestGeneration.get()
        val messageFingerprint = VoiceDiagnostics.fingerprint(interactionId, currentMessage)
        Log.i(
            TAG,
            "conversation_input interactionId=$interactionId chars=${currentMessage.length} " +
                "fingerprint=$messageFingerprint preview=${VoiceDiagnostics.redactedPreview(currentMessage)}"
        )
        val configuration = fetchConfiguration(token, interactionId)
        val filteredHistory = history.takeLast(configuration.maxHistoryMessages)
        val encodedBody = encodeConversationBody(currentMessage, language, filteredHistory, memoryConsentProvider())
        val body = encodedBody.toJsonObject()
        val messages = body.optJSONArray("history") ?: JSONArray()
        val bodyContainsExpectedMessage = body.optString("message") == currentMessage
        Log.i(
            TAG,
            "conversation_body interactionId=$interactionId containsExpectedMessage=$bodyContainsExpectedMessage " +
                "historyCount=${messages.length()} fingerprint=$messageFingerprint"
        )
        if (!bodyContainsExpectedMessage) throw ConversationFailure("GÜNCEL KONUŞMA METNİ İSTEĞE EKLENEMEDİ")
        val response = request(
            "/v1/conversations",
            "POST",
            body,
            CHAT_READ_TIMEOUT_MS,
            token,
            interactionId,
            currentMessage
        )
        val returnedInteractionId = response.optString("interactionId")
        if (returnedInteractionId.isNotEmpty() && returnedInteractionId != interactionId) {
            Log.e(
                TAG,
                "conversation_body_interaction_mismatch interactionId=$interactionId " +
                    "returnedInteractionIdPresent=${returnedInteractionId.isNotEmpty()}"
            )
            throw ConversationFailure("BULUT YANITININ ETKİLEŞİM KİMLİĞİ EŞLEŞMEDİ")
        }
        val text = response.optString("reply").trim()
        if (text.isEmpty()) throw ConversationFailure("BULUT ASİSTANI BOŞ YANIT VERDİ")
        Log.i(
            TAG,
            "conversation_reply interactionId=$interactionId chars=${text.length} " +
                "fingerprint=${VoiceDiagnostics.fingerprint(interactionId, text)} " +
                "preview=${VoiceDiagnostics.redactedPreview(text)}"
        )
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
        token: Long,
        interactionId: String,
        expectedMessage: String? = null
    ): JSONObject {
        if (requestGeneration.get() != token) throw ConversationFailure("KONUŞMA İPTAL EDİLDİ")
        val url = try {
            URL("$baseUrl$path")
        } catch (error: MalformedURLException) {
            throw ConversationFailure("BULUT SUNUCU ADRESİ GEÇERSİZ", error)
        }
        val isHttps = url.protocol.equals("https", ignoreCase = true)
        val isPrivateLanHttp = url.protocol.equals("http", ignoreCase = true) && isPrivateLanIpv4(url.host)
        if (!isHttps && !isPrivateLanHttp) {
            val message = if (url.protocol.equals("http", ignoreCase = true)) {
                "HTTP ADRESİ ÖZEL LAN IP'Sİ OLMALI"
            } else {
                "BULUT SUNUCU HTTPS OLMALI"
            }
            throw ConversationFailure(message)
        }
        if (url.userInfo != null || url.query != null || url.ref != null) {
            throw ConversationFailure("BULUT SUNUCU ADRESİ KİMLİK BİLGİSİ İÇERMEMELİ")
        }
        val requestId = UUID.randomUUID().toString()
        val route = if (path == "/v1/config") "config" else "conversation"
        Log.i(TAG, "http_request_started interactionId=$interactionId requestId=$requestId route=$route host=${url.host}")

        val connection = try {
            url.openConnection() as? HttpURLConnection
                ?: throw ConversationFailure("BULUT SUNUCUSUNA BAĞLANILAMADI")
        } catch (error: ConversationFailure) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "http_connection_failed interactionId=$interactionId requestId=$requestId type=${error.javaClass.simpleName}"
            )
            throw ConversationFailure("BULUT SUNUCUSUNA BAĞLANILAMADI", error)
        }
        activeConnection = connection
        try {
            if (requestGeneration.get() != token) throw ConversationFailure("KONUŞMA İPTAL EDİLDİ")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Request-ID", requestId)
            connection.setRequestProperty("X-Interaction-ID", interactionId)
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            accessTokenProvider().trim().takeIf(String::isNotEmpty)?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                expectedMessage?.let {
                    connection.setRequestProperty(
                        "X-Message-Fingerprint",
                        VoiceDiagnostics.fingerprint(interactionId, it)
                    )
                }
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val returnedRequestId = connection.getHeaderField("X-Request-ID")
            val returnedInteractionId = connection.getHeaderField("X-Interaction-ID")
            val interactionIdEchoed = !returnedInteractionId.isNullOrEmpty()
            val interactionIdMatches = returnedInteractionId == interactionId
            Log.i(
                TAG,
                "http_response_received interactionId=$interactionId requestId=$requestId route=$route " +
                    "status=$code requestIdMatches=${returnedRequestId == requestId} " +
                    "interactionIdEchoed=$interactionIdEchoed interactionIdMatches=$interactionIdMatches"
            )
            // Earlier local Compose images echo X-Request-ID but predate interaction ID echoing.
            // A present interaction ID must match; the unique request ID remains mandatory.
            if (returnedRequestId != requestId || (interactionIdEchoed && !interactionIdMatches)) {
                throw ConversationFailure("BULUT YANITININ İSTEK KİMLİĞİ EŞLEŞMEDİ")
            }
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
            if (code !in 200..299) throw serverFailure(code, responseText, requestId, interactionId)
            return JSONObject(responseText)
        } catch (error: SocketTimeoutException) {
            Log.w(TAG, "http_timeout interactionId=$interactionId requestId=$requestId route=$route")
            throw ConversationFailure("SUNUCU YANITI ZAMAN AŞIMINA UĞRADI", error)
        } catch (error: UnknownHostException) {
            Log.w(TAG, "http_host_not_found interactionId=$interactionId requestId=$requestId route=$route")
            throw ConversationFailure("SUNUCU ADRESİ BULUNAMADI", error)
        } catch (error: ConnectException) {
            Log.w(TAG, "http_connection_refused interactionId=$interactionId requestId=$requestId route=$route")
            throw ConversationFailure("SUNUCU BAĞLANTIYI KABUL ETMEDİ", error)
        } catch (error: NoRouteToHostException) {
            Log.w(TAG, "http_no_route interactionId=$interactionId requestId=$requestId route=$route")
            throw ConversationFailure("YEREL AĞDA SUNUCUYA ULAŞILAMADI", error)
        } catch (error: UnknownServiceException) {
            Log.w(TAG, "http_service_not_allowed interactionId=$interactionId requestId=$requestId route=$route")
            throw ConversationFailure("HTTP BAĞLANTISINA İZİN VERİLMİYOR", error)
        } catch (error: JSONException) {
            Log.w(TAG, "http_invalid_json interactionId=$interactionId requestId=$requestId route=$route")
            throw ConversationFailure("BULUT SUNUCUSU YANITI OKUNAMADI", error)
        } catch (error: ConversationFailure) {
            throw error
        } catch (error: IOException) {
            Log.w(
                TAG,
                "http_transfer_failed interactionId=$interactionId requestId=$requestId route=$route " +
                    "type=${error.javaClass.simpleName}"
            )
            throw ConversationFailure("BULUT ASİSTANINA ULAŞILAMADI", error)
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun serverFailure(code: Int, body: String, requestId: String, interactionId: String): ConversationFailure {
        val errorCode = try {
            JSONObject(body).optJSONObject("error")?.optString("code").orEmpty()
        } catch (_: JSONException) {
            ""
        }
        val diagnosticCode = errorCode.takeIf { value ->
            value.length in 1..64 && value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        } ?: "bilinmiyor"
        Log.w(TAG, "backend_error interactionId=$interactionId requestId=$requestId status=$code code=$diagnosticCode")
        val message = when {
            errorCode == "companion_auth_required" -> "SUNUCU ERİŞİM ANAHTARI GEREKLİ"
            errorCode == "quota_exceeded" -> "AI KOTASI TÜKENDİ; SINIRLAR SIFIRLANINCA YENİDEN DENE"
            code == 429 || errorCode == "rate_limited" -> "AI SAĞLAYICISI ŞU ANDA YOĞUN"
            errorCode == "missing_provider_credentials" -> "BULUT AI KİMLİK BİLGİLERİ YAPILANDIRILMAMIŞ"
            errorCode == "invalid_provider_credentials" -> "BULUT AI KİMLİK BİLGİLERİ GEÇERSİZ"
            errorCode == "unsupported_model" -> "SEÇİLEN AI MODELİ DESTEKLENMİYOR"
            errorCode == "unsupported_provider" -> "AI SAĞLAYICISI DESTEKLENMİYOR"
            errorCode == "provider_timeout" || code == 504 -> "AI SAĞLAYICISI ZAMAN AŞIMINA UĞRADI"
            errorCode == "provider_unavailable" -> "AI SAĞLAYICISI GEÇİCİ OLARAK YANIT VERMİYOR"
            errorCode == "incomplete_provider_response" -> "AI YANITI YARIM KALDI; TEKRAR DENE"
            errorCode == "empty_provider_response" -> "AI BOŞ YANIT VERDİ; TEKRAR DENE"
            errorCode == "invalid_provider_response" -> "AI YANITI OKUNAMADI"
            errorCode == "provider_request_failed" -> "AI İSTEĞİ İŞLENEMEDİ"
            code == 503 -> "BULUT ASİSTANI HAZIR DEĞİL"
            code in 400..499 -> "BULUT İSTEĞİ KABUL EDİLMEDİ"
            else -> "BULUT ASİSTANINA ŞU ANDA ULAŞILAMIYOR"
        }
        return ConversationFailure(message, code = errorCode.takeIf(String::isNotEmpty))
    }

    private companion object {
        const val TAG = "VoiceInteraction"
        const val CONNECT_TIMEOUT_MS = 7_000
        const val CONFIG_READ_TIMEOUT_MS = 5_000
        const val CHAT_READ_TIMEOUT_MS = 45_000
        const val MAX_RESPONSE_CHARS = 64 * 1024
        const val MAX_HISTORY_MESSAGES = 8
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Local HTTP is allowed only for RFC 1918 IPv4 hosts used on a trusted LAN. */
        fun isPrivateLanIpv4(host: String): Boolean {
            val octets = host.split('.')
            if (octets.size != 4) return false
            val address = octets.map { octet ->
                if (octet.isEmpty() || octet.any { it !in '0'..'9' } ||
                    (octet.length > 1 && octet[0] == '0')) return false
                octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
            }
            return address[0] == 10 ||
                (address[0] == 172 && address[1] in 16..31) ||
                (address[0] == 192 && address[1] == 168)
        }
    }
}

internal object ConversationEngineFactory {
    fun create(
        endpoint: String,
        useMock: Boolean = false,
        accessTokenProvider: () -> String = { "" },
        memoryConsentProvider: () -> Boolean = { false }
    ): ConversationEngine {
        return when (modeFor(endpoint, useMock)) {
            ConversationEngineMode.HTTP -> HttpConversationEngine(endpoint, accessTokenProvider, memoryConsentProvider)
            ConversationEngineMode.MOCK -> MockConversationEngine()
            ConversationEngineMode.UNCONFIGURED -> UnconfiguredConversationEngine()
        }
    }

    fun modeFor(endpoint: String, useMock: Boolean = false): ConversationEngineMode = when {
        useMock -> ConversationEngineMode.MOCK
        endpoint.isBlank() -> ConversationEngineMode.UNCONFIGURED
        else -> ConversationEngineMode.HTTP
    }
}

internal enum class ConversationEngineMode { HTTP, MOCK, UNCONFIGURED }
