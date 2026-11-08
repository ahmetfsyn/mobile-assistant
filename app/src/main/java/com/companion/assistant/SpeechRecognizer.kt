package com.companion.assistant

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL

/** Implementations run on the voice controller's worker thread. */
internal interface SpeechRecognizer {
    @Throws(IOException::class)
    fun recognize(recording: AudioRecording, language: VoiceLanguage): String
}

internal class SpeechRecognitionFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Deterministic development path used when no cloud endpoint is configured. */
internal class MockSpeechRecognizer : SpeechRecognizer {
    override fun recognize(recording: AudioRecording, language: VoiceLanguage): String {
        if (recording.pcm16le.isEmpty()) throw SpeechRecognitionFailure("Ses kaydı boş.")
        return VoiceConfig.mockTranscription(language)
    }
}

/**
 * Provider-neutral cloud adapter. The endpoint accepts raw 16-bit little-endian mono PCM and
 * responds with a JSON object containing a `text` string. See VOICE_SETUP.md for the contract.
 */
internal class HttpSpeechRecognizer(
    private val endpoint: String,
    private val bearerToken: String
) : SpeechRecognizer {

    override fun recognize(recording: AudioRecording, language: VoiceLanguage): String {
        if (recording.pcm16le.isEmpty()) throw SpeechRecognitionFailure("Ses kaydı boş.")

        val endpointUrl = try {
            URL(endpoint)
        } catch (error: MalformedURLException) {
            throw SpeechRecognitionFailure("Ses tanıma adresi geçersiz.", error)
        }
        if (!endpointUrl.protocol.equals("https", ignoreCase = true)) {
            throw SpeechRecognitionFailure("Bulut ses tanıma adresi HTTPS olmalı.")
        }
        val connection = try {
            endpointUrl.openConnection() as? HttpURLConnection
                ?: throw SpeechRecognitionFailure("Ses tanıma sunucusuna bağlanılamadı.")
        } catch (error: SpeechRecognitionFailure) {
            throw error
        } catch (error: Exception) {
            throw SpeechRecognitionFailure("Ses tanıma sunucusuna bağlanılamadı.", error)
        }
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "audio/pcm; encoding=s16le; rate=${recording.sampleRate}; channels=1"
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Speech-Language", language.tag)
            if (bearerToken.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            connection.setFixedLengthStreamingMode(recording.pcm16le.size)
            connection.outputStream.use { output -> output.write(recording.pcm16le) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw SpeechRecognitionFailure("Ses tanıma sunucusu hata verdi ($responseCode).")
            }

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(1024)
                var count = reader.read(buffer)
                while (count >= 0) {
                    if (body.length + count > MAX_RESPONSE_CHARS) {
                        throw SpeechRecognitionFailure("Ses tanıma yanıtı çok uzun.")
                    }
                    body.append(buffer, 0, count)
                    count = reader.read(buffer)
                }
                body.toString()
            }
            val text = JSONObject(response).optString("text").trim()
            if (text.isEmpty()) throw SpeechRecognitionFailure("Konuşma anlaşılamadı.")
            return text
        } catch (error: SocketTimeoutException) {
            throw SpeechRecognitionFailure("Ses tanıma zaman aşımına uğradı.", error)
        } catch (error: UnknownHostException) {
            throw SpeechRecognitionFailure("İnternet bağlantısı yok.", error)
        } catch (error: org.json.JSONException) {
            throw SpeechRecognitionFailure("Ses tanıma yanıtı okunamadı.", error)
        } catch (error: SpeechRecognitionFailure) {
            throw error
        } catch (error: IOException) {
            throw SpeechRecognitionFailure("Ses tanıma hizmetine ulaşılamadı.", error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_RESPONSE_CHARS = 64 * 1024
    }
}

internal object SpeechRecognizerFactory {
    fun create(): SpeechRecognizer {
        val endpoint = VoiceConfig.cloudSpeechEndpoint.trim()
        return if (endpoint.isEmpty()) {
            Log.i("VoiceInteraction", "Ses tanıma deneme kipinde; kayıt örnek metne dönüştürülecek.")
            MockSpeechRecognizer()
        } else {
            Log.i("VoiceInteraction", "HTTPS ses tanıma servisi seçildi.")
            HttpSpeechRecognizer(endpoint, VoiceConfig.cloudSpeechBearerToken.trim())
        }
    }
}
