package com.robotuth.v3.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.domain.model.AppSettings
import com.robotuth.v3.domain.model.SttMode
import com.robotuth.v3.domain.repository.SettingsRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(
    context: Context
) : SettingsRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val preferencesFlow: Flow<String> = callbackFlow {
        trySend("") // Emit initial value to trigger load on subscription
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            key?.let { trySend(it) }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override val settings: Flow<AppSettings> = preferencesFlow
        .map { loadSettings() }

    private fun loadSettings(): AppSettings {
        val savedServerUrl = prefs.getString(KEY_SERVER_URL, null)
        val isLegacy = savedServerUrl == AppConfig.LEGACY_TAILSCALE_API_BASE_URL ||
            savedServerUrl == AppConfig.LEGACY_FUNNEL_API_BASE_URL
        val serverUrl = when {
            savedServerUrl == null || isLegacy -> AppConfig.DEFAULT_API_BASE_URL
            else -> savedServerUrl
        }

        // Existing installations persist an old Tailscale/funnel address. Migrate it
        // once so every API client (chat, face, ASR and TTS) uses the LAN gateway.
        if (isLegacy) {
            prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply()
        }

        return AppSettings(
            sttRestartDelayMs = prefs.getInt(KEY_STT_RESTART_DELAY, 3000),
            sttWaitDelayMs = prefs.getInt(KEY_STT_WAIT_DELAY, 500),
            isFaceAutoDetectEnabled = prefs.getBoolean(KEY_FACE_AUTO_DETECT, false),
            isSttAutoEnabled = prefs.getBoolean(KEY_STT_AUTO_ENABLED, false),
            currentSttMode = SttMode.CTC,
            serverUrl = serverUrl
        )
    }

    override suspend fun updateSttRestartDelay(delayMs: Int) {
        prefs.edit().putInt(KEY_STT_RESTART_DELAY, delayMs).apply()
    }

    override suspend fun updateSttWaitDelay(delayMs: Int) {
        prefs.edit().putInt(KEY_STT_WAIT_DELAY, delayMs).apply()
    }

    override suspend fun updateFaceAutoDetect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FACE_AUTO_DETECT, enabled).apply()
    }

    override suspend fun updateSttAuto(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STT_AUTO_ENABLED, enabled).apply()
    }

    override suspend fun updateSttMode(mode: String) {
        prefs.edit().putString(KEY_STT_MODE, mode).apply()
    }

    override suspend fun updateServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    companion object {
        private const val PREFS_NAME = "robotuth_settings"
        private const val KEY_STT_RESTART_DELAY = "stt_restart_delay"
        private const val KEY_STT_WAIT_DELAY = "stt_wait_delay"
        private const val KEY_FACE_AUTO_DETECT = "face_auto_detect"
        private const val KEY_STT_AUTO_ENABLED = "stt_auto_enabled"
        private const val KEY_STT_MODE = "stt_mode"
        private const val KEY_SERVER_URL = "server_url"
    }
}
