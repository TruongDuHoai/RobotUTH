package com.robotuth.v3.ui.face

import android.graphics.Rect
import androidx.compose.runtime.Immutable
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.domain.model.FaceRecognizeResult

@Immutable
data class FaceUiState(
    val isCameraActive: Boolean = false,
    val isAutoDetectEnabled: Boolean = true,
    val isProcessing: Boolean = false,
    val attendanceState: AttendanceState = AttendanceState.SEARCHING,
    val statusMessage: String = "Đưa mặt vào khung",
    val detectedFaceBounds: FaceBounds? = null,
    val detectedImageWidth: Int = 0,
    val detectedImageHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val lastRecognizeResult: FaceRecognizeResult? = null,
    val recognizedName: String? = null,
    val recognizedConfidence: Float? = null,
    val enrollName: String = "",
    val isEnrollNameValid: Boolean = false,
    val faceServerUrl: String = AppConfig.DEFAULT_API_BASE_URL,
    val cameraFacing: CameraFacing = CameraFacing.FRONT,
    val hasRecognized: Boolean = false
) {
    val canEnroll: Boolean
        get() = enrollName.isNotBlank() && !isProcessing

    val canRecognize: Boolean
        get() = !isProcessing

    val canDetect: Boolean
        get() = !isProcessing
}

@Immutable
enum class AttendanceState {
    SEARCHING,
    POSITIONING,
    TOO_CLOSE,
    TOO_FAR,
    RECOGNIZING,
    SUCCESS,
    RETRY,
    MULTI_FACE
}

@Immutable
data class FaceBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    companion object {
        fun fromRect(rect: Rect): FaceBounds = FaceBounds(
            left = rect.left.toFloat(),
            top = rect.top.toFloat(),
            right = rect.right.toFloat(),
            bottom = rect.bottom.toFloat()
        )
    }
}

@Immutable
enum class CameraFacing {
    FRONT,
    BACK
}
