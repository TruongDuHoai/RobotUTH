package com.robotuth.v3.domain.repository

import com.robotuth.v3.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun updateSttRestartDelay(delayMs: Int)
    suspend fun updateSttWaitDelay(delayMs: Int)
    suspend fun updateFaceAutoDetect(enabled: Boolean)
    suspend fun updateSttAuto(enabled: Boolean)
    suspend fun updateSttMode(mode: String)
    suspend fun updateServerUrl(url: String)
}
