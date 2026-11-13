package com.companion.assistant

/** Converts generated or local display text into a clean string that is safe to send to TTS. */
internal object SpokenTextNormalizer {
    private val fencedCode = Regex("(?s)```.*?```")
    private val image = Regex("!\\[[^]]*]\\([^)]*\\)")
    private val markdownLink = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val referenceLink = Regex("\\[([^]]+)]\\[[^]]*]")
    private val htmlTag = Regex("</?[A-Za-z][^>]*>")
    private val rawUrl = Regex("(?i)(?:https?://|www\\.)[^\\s)\\]]+")
    private val heading = Regex("(?m)^ {0,3}#{1,6}[ \\t]*")
    private val listMarker = Regex("(?m)^\\s*(?:[-+*•]|\\d+[.)])\\s+")
    private val quoteMarker = Regex("(?m)^\\s*>\\s?")
    private val repeatedPunctuation = Regex("([!?.,;:])\\1+")
    private val whitespace = Regex("\\s+")
    private val jsonTextField = Regex(
        "\"(?:spokenText|text|reply|message|content)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
        RegexOption.IGNORE_CASE
    )

    fun normalize(displayText: String): String {
        if (displayText.isBlank()) return ""
        var text = extractTextFromJson(displayText.trim()) ?: displayText
        text = text
            .replace(fencedCode, " ")
            .replace(image, " ")
            .replace(markdownLink, "$1")
            .replace(referenceLink, "$1")
            .replace(heading, "")
            .replace(listMarker, "")
            .replace(quoteMarker, "")
            .replace(htmlTag, " ")
            .replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), " ")
            .replace(Regex("\\*\\*(.*?)\\*\\*|__(.*?)__"), "$1$2")
            .replace(Regex("~~(.*?)~~"), "$1")
            .replace(Regex("(?<!\\w)\\*([^*]+)\\*(?!\\w)|(?<!\\w)_([^_]+)_(?!\\w)"), "$1$2")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("\\\\([*_#`~\\\\])"), "$1")
            .replace(Regex("(?i)&amp;"), " ve ")
            .replace(Regex("(?i)&(?:lt|gt|quot|apos);"), " ")
            .replace(Regex("&#(?:x([0-9a-f]+)|(\\d+));", RegexOption.IGNORE_CASE)) { match ->
                val codePoint = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull(16)
                    ?: match.groupValues[2].toIntOrNull()
                codePoint?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: " "
            }
            .replace(rawUrl) { match ->
                val trailing = match.value.takeLastWhile { it in ".,;:!?" }
                " $trailing"
            }
            .replace('|', ' ')
        text = removeEmojiAndDecorativeSymbols(text)
            .replace(Regex("[\\[\\]{}]"), " ")
            .replace(Regex("(?<!\\d)[*_](?!\\d)"), " ")
            .replace(repeatedPunctuation, "$1")
            .replace(Regex("([!?])(?=\\S)"), "$1 ")
            .replace(Regex("\\.(?=[\\p{Lu}])"), ". ")
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(whitespace, " ")
            .trim()
        return text.takeUnless(::isFormattingOnly).orEmpty()
    }

    private fun extractTextFromJson(text: String): String? {
        if (!(text.startsWith('{') && text.endsWith('}') || text.startsWith('[') && text.endsWith(']'))) return null
        val match = jsonTextField.find(text) ?: return ""
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun removeEmojiAndDecorativeSymbols(text: String): String {
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (!isEmojiOrDecoration(codePoint)) result.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun isEmojiOrDecoration(codePoint: Int): Boolean = when {
        codePoint in 0x1F000..0x1FAFF -> true
        codePoint in 0x2600..0x27BF -> true
        codePoint in 0x2300..0x23FF -> true
        codePoint in 0x1F1E6..0x1F1FF -> true
        codePoint in 0xFE00..0xFE0F -> true
        codePoint in 0xE0100..0xE01EF -> true
        codePoint == 0x200D || codePoint == 0x20E3 || codePoint == 0x00AD -> true
        codePoint == 0x2022 || codePoint == 0x25AA || codePoint == 0x25AB -> true
        else -> false
    }

    private fun isFormattingOnly(text: String): Boolean =
        text.isBlank() || text.none { Character.isLetterOrDigit(it) }
}
