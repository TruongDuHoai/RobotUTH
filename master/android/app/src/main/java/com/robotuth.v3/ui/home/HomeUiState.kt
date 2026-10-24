package com.robotuth.v3.ui.home

enum class ConfigStep {
    Password,
    ServerUrl
}

data class HomeUiState(
    val showConfigDialog: Boolean = false,
    val configStep: ConfigStep = ConfigStep.Password,
    val serverUrlInput: String = "",
    val passwordError: String? = null,
    val saveMessage: String? = null,
    val showIssueDialog: Boolean = false,
    val issueSubmitting: Boolean = false,
    val issueError: String? = null
)
