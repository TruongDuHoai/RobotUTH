package com.robotuth.v3.stt.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kiểm chứng WakeWordGate: "hello" phải bắt được các biến thể ASR tiếng Việt trả
 * về, nhưng KHÔNG được khớp hội thoại nền ("xin chào", "hello" giữa câu).
 */
class WakeWordGateTest {

    private val gate = WakeWordGate()

    @Test
    fun helloWithQuestionStripsWakeAndKeepsAccents() {
        val m = gate.evaluate("Hello, cho tôi hỏi phòng truyền thông ở đâu?")
        assertEquals("cho tôi hỏi phòng truyền thông ở đâu?", m?.question)
    }

    @Test
    fun helloVariantsMatch() {
        for (t in listOf(
            "Helo, chỉ giùm tôi nhà A",
            "Hê lô, còn mở cửa không?",
            "A lô, phòng 119 ở đâu",
            "HALO! giúp tôi với",
            "Rô bốt, giờ làm việc"
        )) {
            assertEquals("must wake on: $t", true, gate.evaluate(t) != null)
        }
    }

    @Test
    fun bareWakeLeavesEmptyQuestion() {
        val m = gate.evaluate("hello")
        assertEquals("", m?.question)
    }

    @Test
    fun ambientGreetingDoesNotWake() {
        // "chào" KHÔNG phải mặc định (nhân viên chào nhau mỗi phút).
        assertNull(gate.evaluate("Xin chào anh, cho em hỏi chút"))
        assertNull(gate.evaluate("Chào buổi sáng cả nhà"))
    }

    @Test
    fun helloMidSentenceDoesNotWake() {
        // "hello" không ở đầu câu (maxLeadingWords = 2).
        assertNull(gate.evaluate("Hôm nay trời đẹp hello nhé mọi người"))
    }

    @Test
    fun questionWithoutWakeIsIgnored() {
        assertNull(gate.evaluate("Phòng truyền thông ở đâu vậy"))
    }

    @Test
    fun emptyAndNoiseReturnNull() {
        assertNull(gate.evaluate(""))
        assertNull(gate.evaluate("   ... "))
        assertNull(gate.evaluate("ừm ừm"))
    }

    @Test
    fun normalizeStripsDiacriticsAndPunctuation() {
        assertEquals("ha lo ro bot", WakeWordGate.normalize("Hà-lô  Rô-bốt!"))
    }
}
