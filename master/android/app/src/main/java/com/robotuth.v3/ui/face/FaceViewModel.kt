package com.robotuth.v3.ui.face

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotuth.v3.api.RetrofitClient
import com.robotuth.v3.domain.model.FaceRecognizeResult
import com.robotuth.v3.domain.repository.SettingsRepository
import com.robotuth.v3.domain.usecase.DetectFaceUseCase
import com.robotuth.v3.domain.usecase.EnrollFaceUseCase
import com.robotuth.v3.domain.usecase.RecognizeFaceUseCase
import com.robotuth.v3.repository.FaceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FaceViewModel(
    internal val detectFaceUseCase: DetectFaceUseCase,
    internal val enrollFaceUseCase: EnrollFaceUseCase,
    internal val recognizeFaceUseCase: RecognizeFaceUseCase,
    private val settingsRepository: SettingsRepository,
    private val faceRepository: FaceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FaceUiState())
    val uiState: StateFlow<FaceUiState> = _uiState.asStateFlow()

    private var isRecognizing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var autoDismissJob: Job? = null
    private var autoRetryJob: Job? = null

    companion object {
        private const val AUTO_DISMISS_MS = 1500L
        private const val AUTO_RETRY_MS = 1500L
        private const val MIN_FACE_RATIO = 0.08f
        private const val MAX_FACE_RATIO = 0.70f
    }

    init {
        _uiState.update {
            it.copy(
                faceServerUrl = RetrofitClient.getBaseUrl(),
                isAutoDetectEnabled = true,
                attendanceState = AttendanceState.SEARCHING,
                statusMessage = "Đưa mặt vào khung"
            )
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { _ ->
                _uiState.update { it.copy(isAutoDetectEnabled = true) }
            }
        }
    }

    fun onEvent(event: FaceUiEvent) {
        when (event) {
            is FaceUiEvent.OnCameraInitialized   -> handleCameraInitialized()
            is FaceUiEvent.OnCameraStarted        -> handleCameraStarted()
            is FaceUiEvent.OnCameraStopped        -> handleCameraStopped()
            is FaceUiEvent.OnSwitchCamera         -> handleSwitchCamera()
            is FaceUiEvent.OnDetectClick          -> handleDetectClick()
            is FaceUiEvent.OnEnrollClick          -> handleEnrollClick()
            is FaceUiEvent.OnRecognizeClick       -> handleRecognizeClick()
            is FaceUiEvent.OnEnrollNameChanged    -> handleEnrollNameChanged(event.name)
            is FaceUiEvent.OnAutoDetectToggle     -> handleAutoDetectToggle(event.enabled)
            is FaceUiEvent.OnFaceServerUrlChanged -> handleFaceServerUrlChanged(event.url)
            is FaceUiEvent.OnFaceServerConnect    -> handleFaceServerConnect()
            is FaceUiEvent.OnRefreshHistory       -> { /* removed */ }
            is FaceUiEvent.OnImageCaptured        -> { /* handled by use cases */ }
            is FaceUiEvent.OnAutoDetectFaceFound  -> handleAutoDetectFaceFound(event.bitmap)
            is FaceUiEvent.OnFaceDetected         -> handleFaceDetected(event)
            is FaceUiEvent.OnNoFaceDetected       -> handleNoFaceDetected()
            is FaceUiEvent.OnMultipleFaces        -> handleMultipleFaces()
            is FaceUiEvent.OnDetectComplete       -> handleDetectComplete(event)
            is FaceUiEvent.OnEnrollComplete       -> handleEnrollComplete(event)
            is FaceUiEvent.OnRecognizeComplete    -> handleRecognizeComplete(event.result)
            is FaceUiEvent.OnError                -> handleError(event.message)
            is FaceUiEvent.OnClearResult          -> handleClearResult()
            is FaceUiEvent.OnDismissAttendanceDialog -> handleDismissAttendanceDialog()
        }
    }

    // ── Camera state ──────────────────────────────────────────────────────────

    private fun handleCameraInitialized() {
        _uiState.update { it.copy(isCameraActive = true) }
    }

    private fun handleCameraStarted() {
        _uiState.update { it.copy(isCameraActive = true) }
    }

    /** Reset state only when the user opens a new attendance screen. */
    fun startAttendanceSession() {
        autoDismissJob?.cancel()
        autoRetryJob?.cancel()
        isRecognizing.set(false)
        _uiState.update {
            it.copy(
                isCameraActive = true,
                isAutoDetectEnabled = true,
                isProcessing = false,
                attendanceState = AttendanceState.SEARCHING,
                statusMessage = "Đưa mặt vào khung",
                detectedFaceBounds = null,
                lastRecognizeResult = null,
                recognizedName = null,
                recognizedConfidence = null,
                hasRecognized = false
            )
        }
    }

    private fun handleCameraStopped() {
        _uiState.update { it.copy(isCameraActive = false) }
    }

    private fun handleSwitchCamera() {
        _uiState.update { s ->
            s.copy(cameraFacing = if (s.cameraFacing == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT)
        }
    }

    // ── Button clicks (kept for enroll/detect manual flows) ───────────────────

    private fun handleDetectClick() {
        _uiState.update {
            it.copy(isProcessing = true, statusMessage = "Đang chụp...", attendanceState = AttendanceState.RECOGNIZING)
        }
    }

    private fun handleRecognizeClick() {
        _uiState.update {
            it.copy(isProcessing = true, statusMessage = "Đang nhận diện...", attendanceState = AttendanceState.RECOGNIZING)
        }
    }

    private fun handleEnrollClick() {
        if (_uiState.value.enrollName.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Nhập tên trước", attendanceState = AttendanceState.RETRY) }
            return
        }
        _uiState.update {
            it.copy(isProcessing = true, statusMessage = "Đang đăng ký...", attendanceState = AttendanceState.RECOGNIZING)
        }
    }

    private fun handleEnrollNameChanged(name: String) {
        _uiState.update { it.copy(enrollName = name, isEnrollNameValid = name.trim().isNotEmpty()) }
    }

    // ── Auto detect ───────────────────────────────────────────────────────────

    private fun handleAutoDetectToggle(enabled: Boolean) {
        _uiState.update {
            it.copy(
                isAutoDetectEnabled = enabled,
                attendanceState = if (enabled) AttendanceState.SEARCHING else AttendanceState.SEARCHING,
                statusMessage = if (enabled) "Đưa mặt vào khung" else "Tạm dừng",
                detectedFaceBounds = null
            )
        }
        viewModelScope.launch { settingsRepository.updateFaceAutoDetect(enabled) }
        if (!enabled) {
            isRecognizing.set(false)
            autoDismissJob?.cancel()
            autoRetryJob?.cancel()
        }
    }

    private fun handleAutoDetectFaceFound(bitmap: Bitmap) {
        val state = _uiState.value
        if (!state.isAutoDetectEnabled || isRecognizing.get()) return
        if (state.attendanceState == AttendanceState.SUCCESS) return
        if (!isRecognizing.compareAndSet(false, true)) return

        autoRetryJob?.cancel()
        _uiState.update {
            it.copy(
                isProcessing = true,
                attendanceState = AttendanceState.RECOGNIZING,
                statusMessage = "Đang nhận diện..."
            )
        }
        viewModelScope.launch {
            recognizeFaceUseCase(bitmap)
                .onSuccess { onEvent(FaceUiEvent.OnRecognizeComplete(it)) }
                .onFailure {
                    isRecognizing.set(false)
                    onEvent(FaceUiEvent.OnError(it.message ?: "Recognition failed"))
                }
        }
    }

    // ── Face events ───────────────────────────────────────────────────────────

    private fun handleFaceDetected(event: FaceUiEvent.OnFaceDetected) {
        val state = _uiState.value
        if (state.attendanceState == AttendanceState.SUCCESS || state.attendanceState == AttendanceState.RECOGNIZING) return

        val faceW = event.bounds.width().toFloat()
        val faceH = event.bounds.height().toFloat()
        val imgW = event.imageWidth.toFloat()
        val imgH = event.imageHeight.toFloat()
        val faceRatio = (faceW * faceH) / (imgW * imgH)

        val sizeState = when {
            faceRatio > MAX_FACE_RATIO -> AttendanceState.TOO_CLOSE
            faceRatio < MIN_FACE_RATIO -> AttendanceState.TOO_FAR
            else -> AttendanceState.POSITIONING
        }

        val message = when (sizeState) {
            AttendanceState.TOO_CLOSE -> "Lùi lại một chút"
            AttendanceState.TOO_FAR -> "Đến gần hơn"
            else -> "Giữ nguyên..."
        }

        _uiState.update {
            it.copy(
                detectedFaceBounds = FaceBounds.fromRect(event.bounds),
                detectedImageWidth = event.imageWidth,
                detectedImageHeight = event.imageHeight,
                rotationDegrees = event.rotationDegrees,
                attendanceState = sizeState,
                statusMessage = message
            )
        }
    }

    private fun handleNoFaceDetected() {
        val state = _uiState.value
        if (state.attendanceState == AttendanceState.SUCCESS || state.attendanceState == AttendanceState.RECOGNIZING) return

        _uiState.update {
            it.copy(
                attendanceState = AttendanceState.SEARCHING,
                statusMessage = "Đưa mặt vào khung",
                detectedFaceBounds = null,
                hasRecognized = false
            )
        }
    }

    private fun handleMultipleFaces() {
        val state = _uiState.value
        if (state.attendanceState == AttendanceState.SUCCESS || state.attendanceState == AttendanceState.RECOGNIZING) return

        _uiState.update {
            it.copy(
                attendanceState = AttendanceState.MULTI_FACE,
                statusMessage = "Chỉ 1 người trong khung",
                detectedFaceBounds = null
            )
        }
    }

    // ── Server ────────────────────────────────────────────────────────────────

    private fun handleFaceServerUrlChanged(url: String) {
        _uiState.update { it.copy(faceServerUrl = url) }
    }

    private fun handleFaceServerConnect() {
        val url = _uiState.value.faceServerUrl.trim()
        val uri = Uri.parse(url)
        if (url.isBlank() || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
            _uiState.update {
                it.copy(statusMessage = "URL không hợp lệ", attendanceState = AttendanceState.RETRY)
            }
            return
        }
        RetrofitClient.setBaseUrl(url)
        _uiState.update {
            it.copy(
                faceServerUrl = RetrofitClient.getBaseUrl(),
                statusMessage = "Đã kết nối",
                attendanceState = AttendanceState.SEARCHING
            )
        }
    }

    // ── Results ───────────────────────────────────────────────────────────────

    private fun handleDetectComplete(event: FaceUiEvent.OnDetectComplete) {
        _uiState.update {
            it.copy(
                isProcessing = false,
                statusMessage = event.message,
                attendanceState = if (event.success) AttendanceState.POSITIONING else AttendanceState.SEARCHING
            )
        }
    }

    private fun handleEnrollComplete(event: FaceUiEvent.OnEnrollComplete) {
        _uiState.update {
            it.copy(
                isProcessing = false,
                statusMessage = event.message,
                attendanceState = if (event.success) AttendanceState.SUCCESS else AttendanceState.RETRY
            )
        }
        if (event.success) scheduleAutoDismiss()
    }

    private fun handleRecognizeComplete(result: FaceRecognizeResult) {
        isRecognizing.set(false)

        if (result.recognized) {
            _uiState.update {
                it.copy(
                    isAutoDetectEnabled = false,
                    isProcessing = false,
                    attendanceState = AttendanceState.SUCCESS,
                    statusMessage = "Điểm danh thành công",
                    recognizedName = result.name,
                    recognizedConfidence = result.confidence,
                    lastRecognizeResult = result,
                    hasRecognized = true,
                    detectedFaceBounds = null
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    attendanceState = AttendanceState.RETRY,
                    statusMessage = result.message.takeIf { m -> m.isNotBlank() } ?: "Không nhận diện được",
                    hasRecognized = false
                )
            }
            scheduleAutoRetry()
        }
    }

    private fun handleError(message: String) {
        isRecognizing.set(false)
        _uiState.update {
            it.copy(
                isProcessing = false,
                attendanceState = AttendanceState.RETRY,
                statusMessage = "Lỗi: $message"
            )
        }
        scheduleAutoRetry()
    }

    private fun handleClearResult() {
        autoDismissJob?.cancel()
        autoRetryJob?.cancel()
        isRecognizing.set(false)
        _uiState.update {
            it.copy(
                lastRecognizeResult = null,
                detectedFaceBounds = null,
                hasRecognized = false,
                recognizedName = null,
                recognizedConfidence = null,
                attendanceState = AttendanceState.SEARCHING,
                statusMessage = "Đưa mặt vào khung"
            )
        }
    }

    private fun handleDismissAttendanceDialog() {
        handleClearResult()
    }

    // ── Auto-dismiss / Auto-retry ─────────────────────────────────────────────

    private fun scheduleAutoDismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = viewModelScope.launch {
            delay(AUTO_DISMISS_MS)
            _uiState.update {
                it.copy(
                    attendanceState = AttendanceState.SEARCHING,
                    statusMessage = "Đưa mặt vào khung",
                    recognizedName = null,
                    recognizedConfidence = null,
                    lastRecognizeResult = null,
                    hasRecognized = false,
                    detectedFaceBounds = null
                )
            }
            isRecognizing.set(false)
        }
    }

    private fun scheduleAutoRetry() {
        autoRetryJob?.cancel()
        autoRetryJob = viewModelScope.launch {
            delay(AUTO_RETRY_MS)
            _uiState.update {
                it.copy(
                    attendanceState = AttendanceState.SEARCHING,
                    statusMessage = "Đưa mặt vào khung"
                )
            }
            isRecognizing.set(false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoDismissJob?.cancel()
        autoRetryJob?.cancel()
    }
}
