package com.robotuth.v3.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotuth.v3.api.RetrofitClient
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ChatViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                RetrofitClient.setBaseUrl(settings.serverUrl)
                _uiState.update { state ->
                    state.copy(ctcServerUrl = settings.serverUrl)
                }
            }
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    fun sendMessage() {
        val query = _uiState.value.inputText.trim()
        if (query.isEmpty() || _uiState.value.isLoading) return

        val userMessage = ChatMessage(
            text = query,
            isUser = true,
            time = currentTimeText()
        )

        _uiState.update {
            it.copy(
                inputText = "",
                messages = it.messages + userMessage,
                isLoading = true
            )
        }

        viewModelScope.launch {
            try {
                val baseUrl = _uiState.value.ctcServerUrl
                    .ifBlank { AppConfig.DEFAULT_API_BASE_URL }
                RetrofitClient.setBaseUrl(baseUrl)
                val response = RetrofitClient.getChatApiService(baseUrl).queryChat(query)
                val body = response.body()
                val responseText = when {
                    response.isSuccessful && body != null -> {
                        body.response?.takeIf { it.isNotBlank() }
                            ?: body.message?.takeIf { it.isNotBlank() }
                            ?: "Server không trả về nội dung."
                    }
                    else -> {
                        val err = response.errorBody()?.string()?.take(200)
                        "Lỗi API ${response.code()}: ${err ?: response.message()}"
                    }
                }

                val botMessage = ChatMessage(
                    text = responseText,
                    isUser = false,
                    time = currentTimeText()
                )
                _uiState.update {
                    it.copy(
                        messages = it.messages + botMessage,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                val botMessage = ChatMessage(
                    text = "Lỗi kết nối server: ${e.localizedMessage ?: "Unknown error"}.",
                    isUser = false,
                    time = currentTimeText()
                )
                _uiState.update {
                    it.copy(
                        messages = it.messages + botMessage,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun currentTimeText(): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return LocalTime.now().format(formatter)
    }
}
