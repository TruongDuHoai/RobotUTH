package com.robotuth.v3.domain.model

import androidx.compose.runtime.Immutable
import com.robotuth.v3.config.AppConfig

@Immutable
data class AppSettings(
    val sttRestartDelayMs: Int = 3000,
    val sttWaitDelayMs: Int = 500,
    val isFaceAutoDetectEnabled: Boolean = false,
    val isSttAutoEnabled: Boolean = false,
    val currentSttMode: SttMode = SttMode.CTC,
    val serverUrl: String = AppConfig.DEFAULT_API_BASE_URL
)
