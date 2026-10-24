package com.robotuth.v3.ui.robot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotuth.v3.api.RetrofitClient
import com.robotuth.v3.api.TtsSynthesizeRequest
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.data.stt.CtcSttService
import com.robotuth.v3.data.stt.SttEvent
import com.robotuth.v3.domain.repository.SettingsRepository
import com.robotuth.v3.stt.ParakeetSttService
import com.robotuth.v3.stt.audio.RobotAudioSession
import com.robotuth.v3.stt.audio.WavAudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class RobotFaceViewModel(
    private val settingsRepository: SettingsRepository,
    private val ctcService: CtcSttService,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(RobotFaceUiState())
    val uiState: StateFlow<RobotFaceUiState> = _uiState.asStateFlow()

    private var emotionResetJob: Job? = null
    private var maxRecordJob: Job? = null
    private var sttTimeoutJob: Job? = null
    private var turnJob: Job? = null
    private var lastPartialTranscript: String = ""
    private var processingQuery: Boolean = false
    private var screenActive: Boolean = false
    private val playbackAudio = RobotAudioSession(appContext)
    private val wavPlayer = WavAudioPlayer()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val url = settings.serverUrl.ifBlank { AppConfig.DEFAULT_API_BASE_URL }
                ctcService.setServerUrl(url)
                RetrofitClient.setBaseUrl(url)
                _uiState.update { it.copy(serverUrl = url) }
            }
        }
        viewModelScope.launch {
            ctcService.events.collect { event -> handleSttEvent(event) }
        }
    }

    fun onMicTap() {
        val state = _uiState.value.pipelineState
        when (state) {
            VoicePipelineState.Idle, VoicePipelineState.Error,
            VoicePipelineState.WakeArmed -> startRecording()
            VoicePipelineState.Recording -> stopRecordingAndProcess()
            VoicePipelineState.Thinking, VoicePipelineState.Speaking -> interruptTurnAndRecord()
            else -> {} // ignore taps during processing
        }
    }

    /** Conversation is push-to-talk: never start recording without a user tap. */
    fun onEnterScreen() {
        screenActive = true
        (ctcService as? ParakeetSttService)?.automaticEndpointEnabled = false
        armWakeMonitor()
    }

    // ── Wake word ("hello", app-side gate trên luồng streaming) ──
    //
    // Monitor chỉ sống ở **Idle**: tiến vào Thinking/Speaking là tắt (robot không
    // tự đánh thức bằng chính TTS), và manual tap luôn chiếm mic (guard trong
    // startListening). Toàn bộ policy ở AppConfig — server là transport thuần.

    private fun wakeFlagsOn(): Boolean =
        AppConfig.WAKE_WORD_ENABLED && AppConfig.STREAMING_ASR_ENABLED

    private fun armWakeMonitor() {
        if (!wakeFlagsOn() || !screenActive || !hasMicPermission()) return
        if (_uiState.value.pipelineState != VoicePipelineState.Idle) return
        val ok = (ctcService as? ParakeetSttService)?.startWakeMonitor() ?: false
        if (ok) {
            _uiState.update {
                it.copy(
                    pipelineState = VoicePipelineState.WakeArmed,
                    emotion = RobotEmotion.Idle,
                    isListening = false,
                    statusText = "Nói “${AppConfig.WAKE_WORD_LABEL}” để gọi robot, hoặc chạm mic"
                )
            }
        }
    }

    private fun stopWakeMonitor() {
        (ctcService as? ParakeetSttService)?.stopWakeMonitor()
    }

    private fun disarmWakeMonitorUi() {
        stopWakeMonitor()
        if (_uiState.value.pipelineState == VoicePipelineState.WakeArmed) {
            _uiState.update {
                it.copy(
                    pipelineState = VoicePipelineState.Idle,
                    emotion = RobotEmotion.Idle,
                    isListening = false,
                    statusText = "Chạm mic để nói chuyện"
                )
            }
        }
    }

    private fun interruptTurnAndRecord() {
        turnJob?.cancel()
        turnJob = null
        processingQuery = false
        releasePlayer()
        startRecording()
    }

    fun onTextInput(text: String) {
        if (text.isBlank() || processingQuery) return
        addMessage(text, isUser = true)
        processTextQuery(text)
    }

    fun clearConversation() {
        maxRecordJob?.cancel()
        sttTimeoutJob?.cancel()
        turnJob?.cancel()
        stopWakeMonitor()
        if (ctcService.isListening) ctcService.stopListening()
        releasePlayer()
        lastPartialTranscript = ""
        processingQuery = false
        _uiState.update {
            it.copy(
                messages = emptyList(),
                currentTranscript = "",
                currentResponse = "",
                emotion = RobotEmotion.Idle,
                pipelineState = VoicePipelineState.Idle,
                statusText = "Chạm mic để nói chuyện",
                error = null
            )
        }
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Permission granted — do NOT auto-start mic.
     * Auto-open AudioRecord on AiniRobot often triggers "pip pip" + steals LiveKit path.
     * User must tap the mic button.
     */
    fun onMicPermissionGranted() {
        _uiState.update {
            it.copy(
                statusText = "Chạm mic để nói chuyện",
                error = null
            )
        }
        armWakeMonitor()
    }

    fun onMicPermissionDenied() {
        handlePipelineError(
            "Chưa cấp quyền micro. Vào Cài đặt → Ứng dụng → RobotUTH → Quyền → Micro."
        )
    }

    /**
     * Leave conversation screen or app background — stop OUR mic/TTS only.
     * Do not restart agentservice SpeechService here (caused extra "pip" on Orion).
     * Home restore is handled by [com.robotuth.v3.stt.audio.OrionHomeCompat] when app finishes.
     */
    fun onLeaveScreen() {
        screenActive = false
        (ctcService as? ParakeetSttService)?.automaticEndpointEnabled = false
        maxRecordJob?.cancel()
        sttTimeoutJob?.cancel()
        turnJob?.cancel()
        turnJob = null
        processingQuery = false
        val parakeet = ctcService as? ParakeetSttService
        if (parakeet != null) {
            parakeet.forceStopAndReleaseMic()
        } else if (ctcService.isListening) {
            ctcService.stopListening()
        }
        // Stop player + abandon only if we held playback focus — no SpeechService restart
        releasePlayer(abandonFocus = true, restoreAgent = false)
        lastPartialTranscript = ""
        _uiState.update {
            it.copy(
                pipelineState = VoicePipelineState.Idle,
                emotion = RobotEmotion.Idle,
                isListening = false,
                statusText = "Chạm mic để nói chuyện",
                error = null
            )
        }
    }

    private fun startRecording() {
        lastPartialTranscript = ""
        releasePlayer()

        if (!hasMicPermission()) {
            handlePipelineError("Cần quyền micro để nói chuyện")
            return
        }

        val started = ctcService.startListening()
        if (!started) {
            val detail = (ctcService as? ParakeetSttService)?.lastStartError
                ?: "Không mở được micro"
            handlePipelineError(detail)
            return
        }
        _uiState.update {
            it.copy(
                pipelineState = VoicePipelineState.Recording,
                emotion = RobotEmotion.Listening,
                isListening = true,
                statusText = "Đang nghe... chạm lại để gửi",
                currentTranscript = "",
                error = null
            )
        }
        // Push-to-talk still has a hard cap. Hands-free mode waits indefinitely
        // for speech and lets local endpointing close the utterance.
        maxRecordJob?.cancel()
        if ((ctcService as? ParakeetSttService)?.automaticEndpointEnabled != true) {
            maxRecordJob = viewModelScope.launch {
                delay(20_000)
                if (_uiState.value.pipelineState == VoicePipelineState.Recording) {
                    stopRecordingAndProcess()
                }
            }
        }
    }

    private fun stopRecordingAndProcess() {
        maxRecordJob?.cancel()
        _uiState.update {
            it.copy(
                pipelineState = VoicePipelineState.ProcessingStt,
                emotion = RobotEmotion.Thinking,
                isListening = false,
                statusText = "Đang nhận dạng giọng nói..."
            )
        }
        // Triggers flush of remaining audio → FinalResult / Error
        ctcService.stopListening()

        startSttTimeout()
    }

    private fun handleSttEvent(event: SttEvent) {
        when (event) {
            is SttEvent.CtcPartial -> {
                val text = event.text.trim()
                if (text.isBlank()) return
                // Whole-utterance mode emits one preview before FinalResult.
                lastPartialTranscript = text
                if (_uiState.value.pipelineState == VoicePipelineState.Recording ||
                    _uiState.value.pipelineState == VoicePipelineState.ProcessingStt ||
                    _uiState.value.pipelineState == VoicePipelineState.WakeArmed
                ) {
                    _uiState.update {
                        it.copy(
                            currentTranscript = lastPartialTranscript,
                            statusText = "Đã nghe: $lastPartialTranscript"
                        )
                    }
                }
            }
            is SttEvent.WakeDetected -> {
                if (_uiState.value.pipelineState != VoicePipelineState.WakeArmed) return
                if (event.question.isBlank()) {
                    // Chỉ có "hello" — service mở followup window 8s nhận câu kế.
                    _uiState.update {
                        it.copy(
                            emotion = RobotEmotion.Listening,
                            isListening = true,
                            statusText = "Robot nghe đây — bạn nói đi ạ"
                        )
                    }
                    return
                }
                // wake + câu hỏi cùng một hơi (hoặc followup cuối) = trọn một lượt.
                stopWakeMonitor()
                lastPartialTranscript = event.question
                _uiState.update {
                    it.copy(
                        pipelineState = VoicePipelineState.ProcessingStt,
                        emotion = RobotEmotion.Thinking,
                        isListening = false,
                        currentTranscript = event.question,
                        statusText = "Đã nghe: ${event.question}"
                    )
                }
                addMessage(event.question, isUser = true)
                processTextQuery(event.question)
            }
            is SttEvent.EndpointDetected -> {
                if (_uiState.value.pipelineState == VoicePipelineState.Recording) {
                    maxRecordJob?.cancel()
                    _uiState.update {
                        it.copy(
                            pipelineState = VoicePipelineState.ProcessingStt,
                            emotion = RobotEmotion.Thinking,
                            isListening = false,
                            statusText = "Đã nghe xong, đang nhận dạng..."
                        )
                    }
                    startSttTimeout()
                }
            }
            is SttEvent.FinalResult -> {
                val text = event.text.trim()
                if (_uiState.value.pipelineState != VoicePipelineState.ProcessingStt) return
                sttTimeoutJob?.cancel()
                if (text.isBlank()) {
                    handlePipelineError("Không nhận diện được giọng nói. Vui lòng nói lại.")
                    return
                }
                lastPartialTranscript = text
                _uiState.update {
                    it.copy(currentTranscript = text, statusText = "Đã nghe: $text")
                }
                addMessage(text, isUser = true)
                processTextQuery(text)
            }
            is SttEvent.Error -> {
                val state = _uiState.value.pipelineState
                if (event.isFatal &&
                    (state == VoicePipelineState.Recording ||
                        state == VoicePipelineState.ProcessingStt)
                ) {
                    // If we already have transcript, ignore soft ASR empty after partials
                    if (lastPartialTranscript.isNotBlank() &&
                        state == VoicePipelineState.ProcessingStt
                    ) {
                        return
                    }
                    if (ctcService.isListening) ctcService.stopListening()
                    handlePipelineError(event.message)
                } else if (state == VoicePipelineState.Recording ||
                    state == VoicePipelineState.ProcessingStt
                ) {
                    _uiState.update { it.copy(statusText = event.message) }
                }
            }
            is SttEvent.StatusUpdate -> {
                if (_uiState.value.pipelineState == VoicePipelineState.Recording ||
                    _uiState.value.pipelineState == VoicePipelineState.WakeArmed
                ) {
                    _uiState.update { it.copy(statusText = event.status) }
                }
            }
        }
    }

    private fun processTextQuery(query: String) {
        if (processingQuery) return
        processingQuery = true
        // Mic phải CÒN RỖNG khi robot nghĩ/nói — wake monitor sống là robot tự
        // đánh thức bằng chính tiếng TTS của nó.
        stopWakeMonitor()
        _uiState.update {
            it.copy(
                pipelineState = VoicePipelineState.Thinking,
                emotion = RobotEmotion.Thinking,
                statusText = "Đang suy nghĩ..."
            )
        }

        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            try {
                val baseUrl = _uiState.value.serverUrl
                    .ifBlank { AppConfig.DEFAULT_API_BASE_URL }
                RetrofitClient.setBaseUrl(baseUrl)

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getChatApiService(baseUrl).queryChat(query)
                }
                val body = response.body()
                if (response.isSuccessful && body != null && !body.success) {
                    // Fail-closed (U4): server từ chối trả lời (VD LLM outage).
                    // body.message là diagnostic — KHÔNG đọc thành đáp án,
                    // không đưa vào lịch sử chat.
                    Log.w(TAG, "chat success=false: ${body.message}")
                    handleChatServiceError(baseUrl)
                    return@launch
                }
                val responseText = when {
                    response.isSuccessful && body != null -> {
                        body.response?.takeIf { it.isNotBlank() }
                            ?: "Server không trả về nội dung."
                    }
                    else -> {
                        val err = response.errorBody()?.string()?.take(200)
                        "Lỗi API ${response.code()}: ${err ?: response.message()}"
                    }
                }

                val emotion = inferEmotion(responseText)
                _uiState.update {
                    it.copy(
                        currentResponse = responseText,
                        emotion = emotion,
                        pipelineState = VoicePipelineState.Speaking,
                        statusText = "Đang trả lời..."
                    )
                }
                addMessage(responseText, isUser = false)

                val played = tryPlayTts(baseUrl, responseText)
                if (!played) {
                    // Fallback: timed speaking state without audio
                    val speakDuration = (responseText.length * 50L).coerceIn(1500, 6000)
                    delay(speakDuration)
                }

                setEmotionTemporarily(RobotEmotion.Happy, 2000)
                _uiState.update {
                    it.copy(
                        pipelineState = VoicePipelineState.Idle,
                        statusText = "Chạm mic để nói chuyện"
                    )
                }
                // Hết lượt → cắm lại wake monitor (nếu bật).
                armWakeMonitor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "processTextQuery failed", e)
                handlePipelineError(e.localizedMessage ?: "Lỗi kết nối")
            } finally {
                processingQuery = false
            }
        }
    }

    private suspend fun tryPlayTts(baseUrl: String, text: String): Boolean {
        return try {
            val ttsResponse = withContext(Dispatchers.IO) {
                RetrofitClient.getTtsApiService(baseUrl)
                    .synthesize(TtsSynthesizeRequest(text = text.take(500)))
            }
            if (!ttsResponse.isSuccessful) {
                Log.w(TAG, "TTS HTTP ${ttsResponse.code()}")
                return false
            }
            val bytes = ttsResponse.body()?.bytes() ?: return false
            if (bytes.isEmpty()) return false

            val isMp3 = ttsResponse.headers()["Content-Type"]
                ?.startsWith("audio/mpeg", ignoreCase = true) == true
            val suffix = if (isMp3) "mp3" else "wav"
            val cacheFile = File(appContext.cacheDir, "robot_tts_${System.currentTimeMillis()}.$suffix")
            withContext(Dispatchers.IO) {
                FileOutputStream(cacheFile).use { it.write(bytes) }
            }
            playAudioFile(cacheFile)
            true
        } catch (e: Exception) {
            Log.w(TAG, "TTS play failed: ${e.message}")
            false
        }
    }

    private suspend fun playAudioFile(file: File) {
        try {
            // Only touch audio when actually speaking — never on app open
            playbackAudio.ensureAudibleVolumes()
            playbackAudio.requestPlaybackFocus()
            withContext(Dispatchers.IO) {
                val play: (android.media.AudioAttributes) -> Boolean = { attrs ->
                    if (file.extension.equals("mp3", ignoreCase = true)) {
                        playMp3Blocking(file, attrs)
                    } else {
                        wavPlayer.playBlocking(file, attrs)
                    }
                }
                val ok = play(RobotAudioSession.voiceCommAttrs())
                if (!ok && !Thread.currentThread().isInterrupted) {
                    Log.w(TAG, "VOICE_COMM play failed — fallback USAGE_MEDIA")
                    play(RobotAudioSession.mediaAttrs())
                }
            }
        } finally {
            releasePlayer(abandonFocus = true, restoreAgent = false)
            file.delete()
        }
    }

    private fun playMp3Blocking(file: File, attrs: android.media.AudioAttributes): Boolean {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(attrs)
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            while (player.isPlaying && !Thread.currentThread().isInterrupted) Thread.sleep(50)
            !Thread.currentThread().isInterrupted
        } catch (e: Exception) {
            Log.w(TAG, "MP3 play failed: ${e.message}")
            false
        } finally {
            player.release()
        }
    }

    private fun inferEmotion(text: String): RobotEmotion {
        val lower = text.lowercase()
        return when {
            lower.contains("xin lỗi") || lower.contains("lỗi") -> RobotEmotion.Sad
            lower.contains("vui") || lower.contains("chào") -> RobotEmotion.Happy
            lower.contains("?") -> RobotEmotion.Surprised
            else -> RobotEmotion.Speaking
        }
    }

    /**
     * UX khi chat service lỗi: trạng thái explicit + 1 câu báo lỗi cố định
     * (local, không phải đáp án) qua TTS + re-arm wake. Không bao giờ TTS
     * `message` của server thành câu trả lời.
     */
    private suspend fun handleChatServiceError(baseUrl: String) {
        _uiState.update {
            it.copy(
                pipelineState = VoicePipelineState.Speaking,
                emotion = RobotEmotion.Error,
                statusText = "Hệ thống đang bận — chạm mic để thử lại"
            )
        }
        if (!tryPlayTts(baseUrl, CHAT_ERROR_SPOKEN)) {
            delay(2500)
        }
        _uiState.update {
            it.copy(
                pipelineState = VoicePipelineState.Idle,
                emotion = RobotEmotion.Idle,
                statusText = "Chạm mic để nói chuyện"
            )
        }
        armWakeMonitor()
    }

    private fun handlePipelineError(message: String) {
        processingQuery = false
        maxRecordJob?.cancel()
        sttTimeoutJob?.cancel()
        if (ctcService.isListening) ctcService.stopListening()
        _uiState.update {
            it.copy(
                pipelineState = VoicePipelineState.Error,
                emotion = RobotEmotion.Error,
                isListening = false,
                error = message,
                statusText = "Lỗi: $message"
            )
        }
        setEmotionTemporarily(RobotEmotion.Idle, 3000)
    }

    private fun startSttTimeout() {
        sttTimeoutJob?.cancel()
        sttTimeoutJob = viewModelScope.launch {
            delay(35_000)
            if (_uiState.value.pipelineState == VoicePipelineState.ProcessingStt) {
                handlePipelineError("ASR quá thời gian phản hồi. Kiểm tra kết nối và thử lại.")
            }
        }
    }

    private fun setEmotionTemporarily(emotion: RobotEmotion, durationMs: Long) {
        emotionResetJob?.cancel()
        _uiState.update { it.copy(emotion = emotion) }
        emotionResetJob = viewModelScope.launch {
            delay(durationMs)
            if (_uiState.value.pipelineState == VoicePipelineState.Idle ||
                _uiState.value.pipelineState == VoicePipelineState.Error
            ) {
                _uiState.update {
                    it.copy(
                        emotion = RobotEmotion.Idle,
                        pipelineState = VoicePipelineState.Idle,
                        statusText = "Chạm mic để nói chuyện"
                    )
                }
            }
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        _uiState.update {
            it.copy(messages = it.messages + ConversationMessage(text = text, isUser = isUser))
        }
    }

    private fun releasePlayer(abandonFocus: Boolean = true, restoreAgent: Boolean = false) {
        try {
            wavPlayer.stop()
        } catch (_: Exception) {
        }
        if (abandonFocus) {
            playbackAudio.abandonFocus()
            playbackAudio.resetRouting()
            if (restoreAgent) {
                playbackAudio.release()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        onLeaveScreen()
        ctcService.release()
    }

    companion object {
        private const val TAG = "RobotFaceVM"

        /** Câu báo lỗi cố định khi chat service từ chối (không phải đáp án). */
        private const val CHAT_ERROR_SPOKEN = "Xin lỗi, hệ thống đang bận. Bạn vui lòng thử lại nhé."
    }
}
