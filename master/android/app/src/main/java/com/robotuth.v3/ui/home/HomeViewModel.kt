package com.robotuth.v3.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.api.IssueCreateRequest
import com.robotuth.v3.api.RetrofitClient
import com.robotuth.v3.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val settingsRepository: SettingsRepository,
    private val onServerUrlApplied: (String) -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var tapCount = 0
    private var lastTapTime = 0L

    fun onGreetingTapped() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime > 3000L) {
            tapCount = 0
        }
        lastTapTime = now
        tapCount++

        if (tapCount >= AppConfig.CONFIG_TAP_COUNT) {
            tapCount = 0
            viewModelScope.launch {
                val currentUrl = settingsRepository.settings.first().serverUrl
                _uiState.update {
                    it.copy(
                        showConfigDialog = true,
                        configStep = ConfigStep.Password,
                        serverUrlInput = currentUrl,
                        passwordError = null,
                        saveMessage = null
                    )
                }
            }
        }
    }

    fun onPasswordSubmit(password: String) {
        if (password == AppConfig.CONFIG_PASSWORD) {
            _uiState.update {
                it.copy(configStep = ConfigStep.ServerUrl, passwordError = null)
            }
        } else {
            _uiState.update { it.copy(passwordError = "Mật khẩu không đúng") }
        }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrlInput = url) }
    }

    fun onServerUrlSave() {
        val url = _uiState.value.serverUrlInput.trim()
        if (url.isBlank()) return

        viewModelScope.launch {
            settingsRepository.updateServerUrl(url)
            onServerUrlApplied(url)
            _uiState.update {
                it.copy(
                    showConfigDialog = false,
                    configStep = ConfigStep.Password,
                    saveMessage = "Đã lưu server: $url"
                )
            }
        }
    }

    fun dismissConfigDialog() {
        _uiState.update {
            it.copy(
                showConfigDialog = false,
                configStep = ConfigStep.Password,
                passwordError = null
            )
        }
    }

    fun clearSaveMessage() {
        _uiState.update { it.copy(saveMessage = null) }
    }

    fun showIssueDialog(show: Boolean) {
        _uiState.update { it.copy(showIssueDialog = show, issueError = null) }
    }

    fun submitIssue(identity: String, detail: String, otherNotes: String) {
        if (identity.length < 4 || detail.length < 5) {
            _uiState.update { it.copy(issueError = "Nhập MSSV/CCCD và chi tiết vấn đề.") }
            return
        }
        _uiState.update { it.copy(issueSubmitting = true, issueError = null) }
        viewModelScope.launch {
            try {
                val response = RetrofitClient.getIssueApiService().create(IssueCreateRequest(identity, detail, otherNotes))
                _uiState.update {
                    if (response.isSuccessful) it.copy(showIssueDialog = false, issueSubmitting = false, saveMessage = "Đã gửi phiếu hỗ trợ.")
                    else it.copy(issueSubmitting = false, issueError = "Không gửi được phiếu hỗ trợ.")
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(issueSubmitting = false, issueError = "Không kết nối được máy chủ.") }
            }
        }
    }
}
