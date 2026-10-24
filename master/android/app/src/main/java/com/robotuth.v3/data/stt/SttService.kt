package com.robotuth.v3.data.stt

import kotlinx.coroutines.flow.Flow

// ─── Events ─────────────────────────────────────────────────────────────────

sealed interface SttEvent {
    /** Local VAD detected the end of an utterance; ASR upload is starting. */
    data object EndpointDetected : SttEvent

    /** Kết quả partial từ server (mỗi chunk 5s) */
    data class CtcPartial(
        val text: String,
        val duration: Double,
        val processingTime: Double
    ) : SttEvent

    /** Final result sau khi ghép các partial (hiện tại = partial cuối) */
    data class FinalResult(val text: String) : SttEvent

    /**
     * Wake monitor (chế độ hands-free, chỉ khi [com.robotuth.v3.config.AppConfig.WAKE_WORD_ENABLED])
     * nghe thấy từ đánh thức. [question] = phần còn lại sau "hello" (có thể rỗng
     * nếu người nói chỉ mới đánh thức → chờ câu hỏi ở lượt tiếp theo).
     */
    data class WakeDetected(val question: String) : SttEvent

    /** Lỗi — isFatal = true thì dừng auto mode */
    data class Error(val message: String, val isFatal: Boolean = false) : SttEvent

    /** Trạng thái hiển thị (Đang nghe / Im lặng / Đang gửi...) */
    data class StatusUpdate(val status: String) : SttEvent
}

// ─── Interface CTC STT ───────────────────────────────────────────────────────

/**
 * Interface duy nhất cho CTC Parakeet STT.
 * Implementation: [com.robotuth.v3.stt.ParakeetSttService]
 */
interface CtcSttService {
    /** true khi đang ghi âm */
    val isListening: Boolean

    /** Flow phát events về UI / ViewModel */
    val events: Flow<SttEvent>

    /** Bắt đầu ghi âm và gửi chunk lên server */
    fun startListening(): Boolean

    /** Dừng ghi âm */
    fun stopListening()

    /** Đổi địa chỉ server CTC */
    fun setServerUrl(url: String)

    /** Giải phóng tài nguyên (AudioRecord, Coroutine scope, HTTP client) */
    fun release()
}
