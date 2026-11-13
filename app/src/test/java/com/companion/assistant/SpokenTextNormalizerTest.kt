package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTextNormalizerTest {
    @Test
    fun removesMarkdownFormattingHeadingsListsAndEmojis() {
        assertEquals(
            "Hava durumu 22 derece. Parçalı bulutlu.",
            SpokenTextNormalizer.normalize("## Hava durumu\n- **22 derece.** ☀️\n- _Parçalı bulutlu._")
        )
    }

    @Test
    fun keepsReadableMarkdownLinkLabelsAndRemovesImagesAndUrls() {
        assertEquals(
            "Detaylar için hava raporunu incele.",
            SpokenTextNormalizer.normalize("Detaylar için [hava raporunu](https://example.test/weather) incele. ![güneş](https://example.test/sun.png)")
        )
        assertEquals("Siteyi aç.", SpokenTextNormalizer.normalize("Siteyi aç https://example.test/path?x=2."))
    }

    @Test
    fun removesCodeBlocksAndInlineCodeDelimiters() {
        assertEquals(
            "Saat 09:30. Ayşe.",
            SpokenTextNormalizer.normalize("Saat `09:30`.\n```json\n{\"hour\":9}\n```\nAyşe.")
        )
    }

    @Test
    fun stripsHtmlTagsQuoteMarkersAndRepeatedPunctuation() {
        assertEquals(
            "Cevap: güzelmiş!",
            SpokenTextNormalizer.normalize("> <b>Cevap:</b> güzelmiş!!!")
        )
    }

    @Test
    fun preservesTurkishTextDatesTimesUnitsAndMath() {
        assertEquals(
            "13.11.2026 saat 09:05, sıcaklık 22 °C; 3 × 4 = 12.",
            SpokenTextNormalizer.normalize("13.11.2026 saat 09:05, sıcaklık 22 °C; 3 × 4 = 12.")
        )
    }

    @Test
    fun extractsNaturalTextFromStructuredSpeechPayloadsWithoutSpeakingJsonSyntax() {
        assertEquals(
            "Mersin'de hava 22 derece.",
            SpokenTextNormalizer.normalize("{\"displayText\":\"**Mersin'de hava 22 derece.** ☀️\",\"spokenText\":\"Mersin'de hava 22 derece.\"}")
        )
        assertEquals("", SpokenTextNormalizer.normalize("{\"value\":42}"))
    }

    @Test
    fun returnsEmptyForFormattingOnlyResponsesAndNormalizesWhitespace() {
        assertEquals("", SpokenTextNormalizer.normalize("## **---** ☀️"))
        assertEquals("Merhaba. Nasılsın?", SpokenTextNormalizer.normalize("  Merhaba.\n\nNasılsın??  "))
        assertEquals("Merhaba! Nasılsın?", SpokenTextNormalizer.normalize("Merhaba!Nasılsın?"))
        assertEquals("09:05 ve 13.11.2026", SpokenTextNormalizer.normalize("09:05 ve 13.11.2026"))
    }
}
