package com.robotuth.v3.ui.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.robotuth.v3.data.camera.CameraManager
import com.robotuth.v3.data.mlkit.FaceDetector

/** Các hàm xử lý ảnh cho Face feature — không phụ thuộc Compose. */

fun captureAndProcess(
    cameraManager: CameraManager,
    onCapture: (Bitmap) -> Unit
) {
    cameraManager.captureImage(onCapture)
}

fun runDetect(bitmap: Bitmap, viewModel: FaceViewModel, faceDetector: FaceDetector) {
    faceDetector.detectAsync(bitmap) { result ->
        result.onSuccess { det ->
            if (det.faces.isNotEmpty()) {
                viewModel.onEvent(FaceUiEvent.OnFaceDetected(
                    bounds = det.faces.first().boundingBox,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    rotationDegrees = 0
                ))
                viewModel.onEvent(FaceUiEvent.OnDetectComplete("Face detected", true))
            } else {
                viewModel.onEvent(FaceUiEvent.OnNoFaceDetected)
                viewModel.onEvent(FaceUiEvent.OnDetectComplete("No face detected", false))
            }
        }.onFailure { viewModel.onEvent(FaceUiEvent.OnError("Detection failed: ${it.message}")) }
    }
}

fun runEnroll(bitmap: Bitmap, name: String, viewModel: FaceViewModel, faceDetector: FaceDetector) {
    faceDetector.detectAsync(bitmap) { result ->
        result.onSuccess { det ->
            when {
                det.faces.isEmpty() -> {
                    viewModel.onEvent(FaceUiEvent.OnNoFaceDetected)
                    viewModel.onEvent(FaceUiEvent.OnEnrollComplete(false, null, "No face detected"))
                }
                det.faces.size > 1 -> {
                    viewModel.onEvent(FaceUiEvent.OnMultipleFaces)
                    viewModel.onEvent(FaceUiEvent.OnEnrollComplete(false, null, "Multiple faces, use one face only"))
                }
                else -> viewModel.processEnrollResult(name, bitmap) { ok, n, msg ->
                    viewModel.onEvent(FaceUiEvent.OnEnrollComplete(ok, n, msg))
                }
            }
        }.onFailure { viewModel.onEvent(FaceUiEvent.OnError("Enroll failed: ${it.message}")) }
    }
}

fun runRecognize(bitmap: Bitmap, viewModel: FaceViewModel, faceDetector: FaceDetector) {
    faceDetector.detectAsync(bitmap) { result ->
        result.onSuccess { det ->
            when {
                det.faces.isEmpty() -> {
                    viewModel.onEvent(FaceUiEvent.OnNoFaceDetected)
                    viewModel.onEvent(FaceUiEvent.OnRecognizeComplete(
                        com.robotuth.v3.domain.model.FaceRecognizeResult(
                            recognized = false, name = null, confidence = null,
                            message = "Không phát hiện thấy khuôn mặt"
                        )
                    ))
                }
                det.faces.size > 1 -> {
                    viewModel.onEvent(FaceUiEvent.OnMultipleFaces)
                    viewModel.onEvent(FaceUiEvent.OnRecognizeComplete(
                        com.robotuth.v3.domain.model.FaceRecognizeResult(
                            recognized = false, name = null, confidence = null,
                            message = "Phát hiện nhiều khuôn mặt, vui lòng chỉ để một khuôn mặt trước camera"
                        )
                    ))
                }
                else -> viewModel.processRecognizeResult(bitmap) { res ->
                    viewModel.onEvent(FaceUiEvent.OnRecognizeComplete(res))
                }
            }
        }.onFailure { 
            viewModel.onEvent(FaceUiEvent.OnRecognizeComplete(
                com.robotuth.v3.domain.model.FaceRecognizeResult(
                    recognized = false, name = null, confidence = null,
                    message = "Lỗi nhận dạng: ${it.message}"
                )
            ))
        }
    }
}

fun safeImageProxyToBitmap(image: ImageProxy): Bitmap? = try {
    val source = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees.toFloat()
    if (rotation == 0f) {
        source
    } else {
        val matrix = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            .also { rotated ->
                if (rotated !== source) source.recycle()
            }
    }
} catch (e: Exception) {
    Log.e("FaceImageProcessor", "toBitmap failed", e); null
}
