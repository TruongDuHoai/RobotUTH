package com.robotuth.v3.stt.wake

/**
 * WakeWordGate — cổng "chỉ nói chuyện với robot khi nghe thấy từ đánh thức".
 *
 * Vì sao nằm ở app chứ không phải server: từ đánh thức là **chính sách hội thoại**
 * (chuỗi nào, cửa sổ bao lâu, im lặng bao lâu thì trả mic) — giữ ở client để chỉnh
 * không cần deploy, và relay `/ws/asr/{session}` đã phát `final` từng lượt trên cùng
 * socket (auto mode). Đổi lại: server decode mọi tiếng ồn nền rồi app mới lọc —
 * gate phía server là tối ưu tương lai (RTF 0.2 còn dư nên chưa cấp thiết).
 *
 * Matching phải **mờ**: "hello" qua ASR tiếng Việt ra "helo"/"hê lô"/"ha-lô"…, nên
 * so trên văn bản bỏ dấu + gộp khoảng trắng, khớp **theo token nguyên từ** (không
 * khớp một phần), chỉ trong [maxLeadingWords] từ đầu câu. Mặc định KHÔNG chứa
 * "chào/xin chào": ngoài sảnh nhân viên chào nhau mỗi vài phút — những thứ đó mà
 * đánh thức được robot thì robot không bao giờ được yên.
 */
class WakeWordGate(
    /** Các mẫu đã chuẩn hóa (bỏ dấu), token cách nhau 1 dấu cách. */
    val patterns: List<String> = DEFAULT_PATTERNS,
    /** Không tính là đánh thức nếu từ khóa xuất hiện sau N từ đầu. */
    val maxLeadingWords: Int = 2,
) {

    data class Match(val question: String, val matched: String)

    /** Ưu tiên mẫu dài hơn: "hello robot" phải được cắt CẢ HAI từ trước khi "hello" kịp thắng. */
    private val rankedPatterns: List<List<String>> =
        patterns.map { it.split(' ') }.sortedByDescending { it.size }

    /**
     * @return [Match] (phần còn lại sau khi cắt từ đánh thức, giữ nguyên dấu) nếu
     *         câu khớp wake word ở đầu; null = tiếng ồn nền / câu không đánh thức.
     */
    fun evaluate(text: String): Match? {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }
        for (index in words.indices) {
            if (index > maxLeadingWords) break
            for (tokens in rankedPatterns) {
                if (index + tokens.size > words.size) continue
                if (words.subList(index, index + tokens.size) == tokens) {
                    return Match(stripOriginal(text, index + tokens.size), tokens.joinToString(" "))
                }
            }
        }
        return null
    }

    companion object {
        val DEFAULT_PATTERNS = listOf(
            "hello", "helo", "halo", "he lo", "ha lo", "alo", "a lo", "hi lo",
            "robot", "ro bot",
            "hello robot", "helo robot",
        )

        /** lowercase → bỏ dấu (NFD) → chỉ giữ a-z0-9 → gộp khoảng trắng. */
        fun normalize(text: String): String {
            val lower = text.lowercase().trim()
            val withoutAccents = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "")
            return withoutAccents
                .replace('đ', 'd')
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /** Bỏ [tokensToDrop] từ đầu của **văn bản gốc** (giữ dấu/hoa), cắt punctuation dẫn đầu. */
        private fun stripOriginal(text: String, tokensToDrop: Int): String {
            var rest = text.trim()
            repeat(tokensToDrop) {
                rest = rest.trimStart()
                val idx = rest.indexOf(' ')
                rest = if (idx < 0) "" else rest.substring(idx + 1)
            }
            return rest.trim().trimStart(',', '.', '!', '?', ':', ';', ' ' ).trim()
        }
    }
}
