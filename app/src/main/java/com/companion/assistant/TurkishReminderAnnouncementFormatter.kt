package com.companion.assistant

import java.util.Locale

/** Builds deterministic Turkish reminder copy without changing the saved reminder text. */
internal object TurkishReminderAnnouncementFormatter {
    private val turkish = Locale("tr", "TR")
    private val nominalAccusative = Regex("(?iu)^.*m[ae]y[ıiuü]$")
    private val firstPersonNominalAccusative = Regex("(?iu)^(.+?)(ma|me)m[ıiuü]$")
    private val infinitive = Regex("(?iu)^.*(?:mak|mek)$")
    private val uncertainWords = setOf("acaba", "belki", "olabilir", "sanırım", "şey", "galiba")
    private val imperativeVerbs = setOf(
        "aç", "al", "ara", "araştır", "başla", "başlat", "bak", "bekle", "bin", "bitir", "bırak",
        "bul", "çağır", "çal", "çalış", "çık", "çöz", "dinlen", "doldur", "dön", "düzenle", "dur",
        "et", "gel", "getir", "git", "giy", "gönder", "görüş", "götür", "hazırla", "hazırlan", "iç", "katıl",
        "incele", "kalk", "kapat", "konuş", "koy", "koş", "koru", "kullan", "oku", "öde", "öğren",
        "otur", "paylaş", "pişir", "planla", "sil", "sür", "taşı", "temizle", "topla", "tut", "unutma",
        "uyu", "uyan", "ver", "yap", "yat", "yaz", "ye", "yıka", "ziyaret"
    )
    private val vowels = setOf('a', 'ı', 'o', 'u', 'e', 'i', 'ö', 'ü')

    fun format(message: String?, recurrence: String = "none"): String {
        val hasQuestionMark = message?.contains('?') == true
        val text = clean(message)
        if (text.isNullOrBlank()) return recurring("Hatırlatma zamanı.", recurrence)

        val lower = text.lowercase(turkish)
        val firstPersonAction = formatFirstPersonNominalizedAction(text)
        val sentence = when {
            hasQuestionMark || lower.split(' ').any(uncertainWords::contains) -> fallback(text)
            firstPersonAction != null -> firstPersonAction
            nominalAccusative.matches(lower) -> {
                val nominal = text.dropLast(2).trim()
                if (nominal.isBlank()) fallback(text) else pairedAction(nominal)
            }
            infinitive.matches(lower) -> {
                val nominal = text.dropLast(1).trim()
                if (nominal.isBlank()) fallback(text) else pairedAction(nominal)
            }
            else -> formatImperativeOrTask(text)
        }
        return recurring(sentence, recurrence)
    }

    /** Handles forms such as "egzersiz yapmamı" and "annemi aramamı" without changing storage. */
    private fun formatFirstPersonNominalizedAction(text: String): String? {
        val words = text.split(' ')
        if (words.size < 2) return null
        val ending = firstPersonNominalAccusative.matchEntire(words.last()) ?: return null
        val verbLemma = ending.groupValues[1].lowercase(turkish)
        if (verbLemma !in imperativeVerbs) return null
        val verbalNoun = ending.groupValues[1] + ending.groupValues[2]
        val objectText = words.dropLast(1).joinToString(" ").trim()
        if (verbLemma == "ara") {
            val dative = toSecondPersonDative(objectText)
            if (dative != null) return "${sentenceCase(dative)} telefon etmeyi unutma."
        }
        val listenerFacingObject = toSecondPersonAccusative(objectText)
        return pairedAction("$listenerFacingObject $verbalNoun".trim())
    }

    private fun formatImperativeOrTask(text: String): String {
        val words = text.split(' ')
        val verb = words.last().lowercase(turkish)
        if (verb !in imperativeVerbs || words.size < 2) {
            return if (isPlainTaskLabel(text)) "${sentenceCase(text)} zamanı!" else fallback(text)
        }

        val objectText = words.dropLast(1).joinToString(" ").trim()
        if (verb == "ara") {
            val dative = toSecondPersonDative(objectText)
            if (dative != null) return "${sentenceCase(dative)} telefon etmeyi unutma."
        }

        val listenerFacingObject = toSecondPersonAccusative(objectText)
        val action = "$listenerFacingObject ${nominalize(words.last())}".trim()
        val reminder = accusative(action)
        return when (verb) {
            "al", "ara", "et", "gönder", "katıl", "öde", "yaz" -> "${sentenceCase(reminder)} unutma."
            else -> pairedAction(action)
        }
    }

    private fun pairedAction(nominalPhrase: String): String {
        val phrase = sentenceCase(nominalPhrase)
        return "$phrase zamanı! ${sentenceCase(accusative(nominalPhrase))} unutma."
    }

    private fun nominalize(verb: String): String {
        val lower = verb.lowercase(turkish)
        val suffix = if (lastVowel(lower) in setOf('a', 'ı', 'o', 'u')) "ma" else "me"
        return verb + suffix
    }

    /** The verbal noun ends in a vowel, so its accusative takes the buffer consonant y. */
    private fun accusative(nominalPhrase: String): String {
        val vowel = lastVowel(nominalPhrase.lowercase(turkish)) ?: return nominalPhrase
        return nominalPhrase + "y" + highVowel(vowel)
    }

    /** Converts clear first-person singular possessive objects to the listener's point of view. */
    private fun toSecondPersonAccusative(phrase: String): String {
        val parts = phrase.split(' ')
        val last = parts.lastOrNull() ?: return phrase
        val lower = last.lowercase(turkish)
        val transformed = when {
            lower.length >= 4 && lower.takeLast(3).let { it[0] in "ıiuü" && it[1] == 'm' && it[2] in "ıiuü" } ->
                last.dropLast(3) + last.takeLast(3).take(1) + "n" + last.takeLast(1)
            lower.length >= 3 && lower.takeLast(2).let { it[0] == 'm' && it[1] in "ıiuü" } ->
                last.dropLast(2) + "n" + last.takeLast(1)
            else -> last
        }
        return (parts.dropLast(1) + transformed).joinToString(" ")
    }

    /** Converts a clear first-person possessed direct object into a natural second-person dative. */
    private fun toSecondPersonDative(phrase: String): String? {
        val parts = phrase.split(' ')
        val last = parts.lastOrNull() ?: return null
        val lower = last.lowercase(turkish)
        val converted = when {
            lower.length >= 4 && lower.takeLast(3).let { it[0] in "ıiuü" && it[1] == 'm' && it[2] in "ıiuü" } -> {
                val stem = last.dropLast(3)
                val vowel = lastVowel(stem.lowercase(turkish)) ?: return null
                stem + highVowel(vowel) + "n" + dativeVowel(vowel)
            }
            lower.length >= 3 && lower.takeLast(2).let { it[0] == 'm' && it[1] in "ıiuü" } -> {
                val stem = last.dropLast(2)
                val vowel = lastVowel(stem.lowercase(turkish)) ?: return null
                stem + "n" + dativeVowel(vowel)
            }
            else -> return null
        }
        return (parts.dropLast(1) + converted).joinToString(" ")
    }

    private fun isPlainTaskLabel(text: String): Boolean {
        val words = text.split(' ')
        if (words.size !in 1..4) return false
        if (words.any { word -> word.any { !it.isLetter() && it != '\'' && it != '’' && it != '-' } }) return false
        if (words.any(uncertainWords::contains)) return false
        val finalWord = words.last().lowercase(turkish)
        return listOf("işi", "iş", "lık", "lik", "luk", "lük", "me", "ma", "ımı", "imi", "umu", "ümü", "ı", "i", "u", "ü")
            .any(finalWord::endsWith)
    }

    private fun clean(message: String?): String? {
        val text = message.orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
        if (text.isBlank() || text.length > 240 || text.none(Char::isLetterOrDigit)) return null
        return text
    }

    private fun fallback(text: String) = "Hatırlatma: ${sentenceCase(text)}."

    private fun recurring(sentence: String, recurrence: String): String {
        val prefix = when (recurrence) {
            "daily" -> "Her gün"
            "weekdays" -> "Hafta içi"
            "weekly:MO" -> "Her pazartesi"
            "weekly:TU" -> "Her salı"
            "weekly:WE" -> "Her çarşamba"
            "weekly:TH" -> "Her perşembe"
            "weekly:FR" -> "Her cuma"
            "weekly:SA" -> "Her cumartesi"
            "weekly:SU" -> "Her pazar"
            else -> return sentence
        }
        return "$prefix, ${lowercaseFirst(sentence)}"
    }

    private fun sentenceCase(text: String): String {
        val index = text.indexOfFirst(Char::isLetter)
        if (index < 0) return text
        return text.substring(0, index) + text[index].toString().uppercase(turkish) + text.substring(index + 1)
    }

    private fun lowercaseFirst(text: String): String {
        val index = text.indexOfFirst(Char::isLetter)
        if (index < 0) return text
        return text.substring(0, index) + text[index].toString().lowercase(turkish) + text.substring(index + 1)
    }

    private fun lastVowel(text: String): Char? = text.lastOrNull { it in vowels }

    private fun highVowel(vowel: Char): String = when (vowel) {
        'a', 'ı' -> "ı"
        'e', 'i' -> "i"
        'o', 'u' -> "u"
        'ö', 'ü' -> "ü"
        else -> "i"
    }

    private fun dativeVowel(vowel: Char): String = if (vowel in setOf('e', 'i', 'ö', 'ü')) "e" else "a"
}

internal fun ReminderRecord.announcement(): String =
    TurkishReminderAnnouncementFormatter.format(message, recurrence)

internal enum class ReminderNotificationKind(val title: String) {
    DUE("Hatırlatma zamanı"),
    DEFERRED("Hatırlatma ertelendi"),
    FAILED("Hatırlatma teslim edilemedi")
}

internal data class ReminderNotificationCopy(val title: String, val body: String)

internal object ReminderNotificationFormatter {
    fun format(reminder: ReminderRecord, kind: ReminderNotificationKind = ReminderNotificationKind.DUE) =
        ReminderNotificationCopy(kind.title, reminder.announcement())
}
