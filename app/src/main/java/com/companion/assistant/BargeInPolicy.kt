package com.companion.assistant

import java.util.Locale

/** Rejects recognition hypotheses that closely match audio currently being spoken by the robot. */
internal object BargeInEchoFilter {
    fun isLikelyEcho(transcript: String, spokenResponse: String?): Boolean {
        if (spokenResponse.isNullOrBlank()) return false
        fun tokens(value: String): List<String> = value.lowercase(Locale("tr", "TR"))
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        val candidate = tokens(transcript)
        val response = tokens(spokenResponse).toSet()
        if (candidate.isEmpty() || response.isEmpty()) return false
        val matching = candidate.count { it in response }
        return matching == candidate.size || candidate.size == 1 && matching == 1 ||
            candidate.size >= 3 && matching.toFloat() / candidate.size >= 0.8f
    }
}
