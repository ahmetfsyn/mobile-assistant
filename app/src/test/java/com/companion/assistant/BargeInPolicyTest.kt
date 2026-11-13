package com.companion.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInPolicyTest {
    @Test
    fun suppressesRobotPhraseAndAllowsClearlyDifferentCorrection() {
        val spoken = "Saat şu anda on yedi üç."
        assertTrue(BargeInEchoFilter.isLikelyEcho("saat şu anda", spoken))
        assertTrue(BargeInEchoFilter.isLikelyEcho("on yedi üç", spoken))
        assertFalse(BargeInEchoFilter.isLikelyEcho("Hayır, bugünün tarihini soruyorum", spoken))
        assertFalse(BargeInEchoFilter.isLikelyEcho("9 olsun", "Alarmını 8'e kurma isteğini gönderiyorum"))
    }

    @Test
    fun emptyOutputAndUnrelatedSpeechAreNeverClassifiedAsEcho() {
        assertFalse(BargeInEchoFilter.isLikelyEcho("hayır", null))
        assertFalse(BargeInEchoFilter.isLikelyEcho("başka bir şey", "şu anda saat on bir"))
    }
}
