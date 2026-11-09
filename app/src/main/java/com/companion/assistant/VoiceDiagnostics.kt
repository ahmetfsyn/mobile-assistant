package com.companion.assistant

import java.security.MessageDigest

/** Privacy-preserving metadata shared by the Android HTTP client and conversation controller. */
internal object VoiceDiagnostics {
    private const val HEX = "0123456789abcdef"
    private val whitespace = Regex("\\s+")

    /** The interaction ID salts the short fingerprint so equal phrases in different turns differ. */
    fun fingerprint(interactionId: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$interactionId\u0000$text".toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (index in 0 until 8) {
                val byte = digest[index].toInt() and 0xff
                append(HEX[byte ushr 4])
                append(HEX[byte and 0x0f])
            }
        }
    }

    /** Reveals only the first letter and approximate length of up to five words. */
    fun redactedPreview(text: String, maxWords: Int = 5): String {
        if (text.isBlank() || maxWords <= 0) return "[empty]"
        val words = text.trim().split(whitespace)
        val preview = words.take(maxWords).joinToString(" ") { word ->
            val first = word.firstOrNull()
            if (first == null || !first.isLetter()) {
                "[redacted:${word.length}]"
            } else {
                buildString {
                    append(first)
                    repeat((word.length - 1).coerceAtMost(5)) { append('•') }
                    if (word.length > 6) append('…')
                }
            }
        }
        return if (words.size > maxWords) "$preview …" else preview
    }
}
