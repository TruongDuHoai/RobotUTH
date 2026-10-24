package com.robotuth.v3.ui.face

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

sealed interface FaceUiEvent {
    data object OnCameraInitialized : FaceUiEvent
    data object OnCameraStarted : FaceUiEvent
    data object OnCameraStopped : FaceUiEvent
    data object OnSwitchCamera : FaceUiEvent

    data object OnDetectClick : FaceUiEvent
    data object OnEnrollClick : FaceUiEvent
    data object OnRecognizeClick : FaceUiEvent

    data class OnEnrollNameChanged(val name: String) : FaceUiEvent
    data class OnAutoDetectToggle(val enabled: Boolean) : FaceUiEvent
    data class OnFaceServerUrlChanged(val url: String) : FaceUiEvent
    data object OnFaceServerConnect : FaceUiEvent
    data object OnRefreshHistory : FaceUiEvent

    data class OnImageCaptured(val imageProxy: ImageProxy) : FaceUiEvent
    data class OnAutoDetectFaceFound(val bitmap: Bitmap) : FaceUiEvent
    data class OnFaceDetected(
        val bounds: android.graphics.Rect,
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int
    ) : FaceUiEvent
    data object OnNoFaceDetected : FaceUiEvent
    data object OnMultipleFaces : FaceUiEvent

    data class OnDetectComplete(val message: String, val success: Boolean) : FaceUiEvent
    data class OnEnrollComplete(val success: Boolean, val name: String?, val message: String) : FaceUiEvent
    data class OnRecognizeComplete(val result: com.robotuth.v3.domain.model.FaceRecognizeResult) : FaceUiEvent

    data class OnError(val message: String) : FaceUiEvent
    data object OnClearResult : FaceUiEvent
    data object OnDismissAttendanceDialog : FaceUiEvent
}
