package com.companion.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.UUID

internal data class StoredMemory(
    val id: String,
    val category: String,
    val key: String,
    val value: String,
    val confidence: Double,
    val importance: Int,
    val createdAt: String,
    val updatedAt: String,
    val lastAccessedAt: String?,
    val status: String
)

internal data class MemoryStatus(val available: Boolean, val activeCount: Int)

internal class MemoryApiFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Synchronous JSON client. MainActivity calls it from a single background executor. */
internal class MemoryApiClient(
    endpoint: String,
    private val accessTokenProvider: () -> String = { "" }
) {
    private val baseUrl = endpoint.trim().trimEnd('/')
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun cancelRequests() {
        activeConnection?.disconnect()
    }

    fun status(): MemoryStatus {
        val response = request("/v1/memories/status", "GET")
        return MemoryStatus(
            available = response.optBoolean("available", false),
            activeCount = response.optInt("activeCount", 0).coerceAtLeast(0)
        )
    }

    fun list(): List<StoredMemory> {
        val array = request("/v1/memories?limit=100", "GET").optJSONArray("memories") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.optString("id")
                val category = entry.optString("category")
                val key = entry.optString("key")
                val value = entry.optString("value")
                if (!UUID_PATTERN.matches(id) || category !in CATEGORIES || key.isBlank() || value.isBlank()) continue
                add(
                    StoredMemory(
                        id = id,
                        category = category,
                        key = key,
                        value = value,
                        confidence = entry.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                        importance = entry.optInt("importance", 1).coerceIn(1, 5),
                        createdAt = entry.optString("createdAt"),
                        updatedAt = entry.optString("updatedAt"),
                        lastAccessedAt = entry.optString("lastAccessedAt").takeIf(String::isNotEmpty),
                        status = entry.optString("status")
                    )
                )
            }
        }
    }

    fun updateValue(memory: StoredMemory, value: String) {
        if (!UUID_PATTERN.matches(memory.id)) throw MemoryApiFailure("KAYIT KİMLİĞİ GEÇERSİZ")
        request("/v1/memories/${memory.id}", "PATCH", JSONObject().put("value", value.trim()))
    }

    fun delete(id: String) {
        if (!UUID_PATTERN.matches(id)) throw MemoryApiFailure("KAYIT KİMLİĞİ GEÇERSİZ")
        request("/v1/memories/$id", "DELETE")
    }

    fun clearAll() {
        request("/v1/memories", "DELETE")
    }

    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        if (baseUrl.isEmpty()) throw MemoryApiFailure("BELLEK İÇİN BACKEND ADRESİ YAPILANDIRILMAMIŞ")
        val url = try {
            URL("$baseUrl$path")
        } catch (error: MalformedURLException) {
            throw MemoryApiFailure("BACKEND ADRESİ GEÇERSİZ", error)
        }
        val secure = url.protocol.equals("https", ignoreCase = true)
        val privateLanHttp = url.protocol.equals("http", ignoreCase = true) && isPrivateLanIpv4(url.host)
        if (!secure && !privateLanHttp) throw MemoryApiFailure("BACKEND HTTPS VEYA ÖZEL LAN IP'Sİ OLMALI")
        val baseAddress = try {
            URL(baseUrl)
        } catch (error: MalformedURLException) {
            throw MemoryApiFailure("BACKEND ADRESİ GEÇERSİZ", error)
        }
        if (baseAddress.userInfo != null || baseAddress.query != null || baseAddress.ref != null || url.ref != null) {
            throw MemoryApiFailure("BACKEND ADRESİ GEÇERSİZ")
        }

        val connection = try {
            url.openConnection() as? HttpURLConnection ?: throw MemoryApiFailure("BACKEND BAĞLANTISI KURULAMADI")
        } catch (error: MemoryApiFailure) {
            throw error
        } catch (error: Exception) {
            throw MemoryApiFailure("BACKEND BAĞLANTISI KURULAMADI", error)
        }
        activeConnection = connection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
            connection.setRequestProperty("X-Interaction-ID", UUID.randomUUID().toString())
            accessTokenProvider().trim().takeIf(String::isNotEmpty)?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(2048)
                var count = reader.read(buffer)
                while (count >= 0) {
                    if (result.length + count > MAX_RESPONSE_CHARS) throw MemoryApiFailure("BELLEK YANITI ÇOK UZUN")
                    result.append(buffer, 0, count)
                    count = reader.read(buffer)
                }
                result.toString()
            }.orEmpty()
            if (status !in 200..299) throw MemoryApiFailure(errorMessage(status, text))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (error: MemoryApiFailure) {
            throw error
        } catch (error: JSONException) {
            throw MemoryApiFailure("BELLEK YANITI OKUNAMADI", error)
        } catch (error: Exception) {
            throw MemoryApiFailure("YEREL BELLEK SUNUCUSUNA ULAŞILAMADI", error)
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun errorMessage(status: Int, text: String): String {
        val code = try {
            JSONObject(text).optJSONObject("error")?.optString("code").orEmpty()
        } catch (_: JSONException) {
            ""
        }
        return when {
            code == "companion_auth_required" -> "SUNUCU ERİŞİM ANAHTARI GEREKLİ"
            code == "memory_consent_required" -> "KAYIT İÇİN BELLEK İZNİ GEREKLİ"
            code == "memory_unavailable" || status == 503 -> "YEREL BELLEK DEPOSU KULLANILAMIYOR"
            code == "memory_conflict" -> "BU KATEGORİ VE ANAHTAR İÇİN KAYIT VAR"
            code == "memory_not_found" -> "BELLEK KAYDI BULUNAMADI"
            status in 400..499 -> "BELLEK İSTEĞİ KABUL EDİLMEDİ"
            else -> "BELLEK SUNUCUSU YANIT VERMİYOR"
        }
    }

    private fun isPrivateLanIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val address = octets.map { octet ->
            if (octet.isEmpty() || octet.any { it !in '0'..'9' } || (octet.length > 1 && octet[0] == '0')) return false
            octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return address[0] == 10 || (address[0] == 172 && address[1] in 16..31) ||
            (address[0] == 192 && address[1] == 168)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 7_000
        const val MAX_RESPONSE_CHARS = 256 * 1024
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val CATEGORIES = setOf("profile", "preference", "fact", "routine", "summary")
    }
}
