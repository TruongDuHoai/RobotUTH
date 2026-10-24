package com.robotuth.v3.ui.chat

import com.robotuth.v3.config.AppConfig

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val time: String
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val ctcServerUrl: String = AppConfig.DEFAULT_API_BASE_URL,
    val error: String? = null
)
