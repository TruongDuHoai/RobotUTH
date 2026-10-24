package com.robotuth.v3.data.camera

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.Flow

interface CameraManager {
    val isInitialized: Boolean
    val currentSelector: CameraSelector

    fun initialize()
    fun isReady(): Boolean
    fun startCamera(surfaceProvider: androidx.camera.core.Preview.SurfaceProvider, onError: ((Exception) -> Unit)? = null)
    fun stopCamera()
    fun switchCamera(): CameraSelector
    fun captureImage(onCapture: (Bitmap) -> Unit)
    fun startAnalysis(onFrame: (ImageProxy) -> Unit)
    fun stopAnalysis()
    fun shutdown()
}

sealed interface CameraEvent {
    data class FaceDetected(
        val bounds: android.graphics.Rect,
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int
    ) : CameraEvent
    data object NoFaceDetected : CameraEvent
    data object MultipleFaces : CameraEvent
    data class Error(val message: String) : CameraEvent
}
