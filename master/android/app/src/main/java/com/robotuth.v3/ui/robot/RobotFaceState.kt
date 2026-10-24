package com.robotuth.v3.ui.robot

import androidx.compose.runtime.Immutable

@Immutable
enum class RobotEmotion {
    Idle,
    Listening,
    Thinking,
    Speaking,
    Happy,
    Surprised,
    Sad,
    Error
}

@Immutable
enum class VoicePipelineState {
    Idle,
    /** Wake monitor đang chạy (hands-free chờ “hello”); chỉ bật khi WAKE_WORD_ENABLED. */
    WakeArmed,
    Recording,
    ProcessingStt,
    Thinking,
    Speaking,
    Error
}

@Immutable
data class ConversationMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Immutable
data class RobotFaceUiState(
    val emotion: RobotEmotion = RobotEmotion.Idle,
    val pipelineState: VoicePipelineState = VoicePipelineState.Idle,
    val messages: List<ConversationMessage> = emptyList(),
    val currentTranscript: String = "",
    val currentResponse: String = "",
    val isListening: Boolean = false,
    val statusText: String = "Chạm mic để nói chuyện",
    val error: String? = null,
    val serverUrl: String = "",
    val handsFreeEnabled: Boolean = true
)
