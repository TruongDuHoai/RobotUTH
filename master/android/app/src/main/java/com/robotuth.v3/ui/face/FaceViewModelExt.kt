package com.robotuth.v3.ui.face

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.robotuth.v3.domain.model.FaceRecognizeResult
import kotlinx.coroutines.launch

/**
 * Extension functions của FaceViewModel — bridge sang use cases.
 * Tách ra để giữ FaceViewModel.kt <= 300 dòng.
 */

fun FaceViewModel.processDetectResult(
    bitmap: Bitmap,
    onComplete: (Boolean, String) -> Unit
) {
    viewModelScope.launch {
        detectFaceUseCase(bitmap)
            .onSuccess { onComplete(true, it.message) }
            .onFailure { onComplete(false, it.message ?: "Detection failed") }
    }
}

fun FaceViewModel.processEnrollResult(
    name: String,
    bitmap: Bitmap,
    onComplete: (Boolean, String?, String) -> Unit
) {
    viewModelScope.launch {
        enrollFaceUseCase(name, bitmap)
            .onSuccess { onComplete(it.success, it.name, it.message) }
            .onFailure { onComplete(false, null, it.message ?: "Enrollment failed") }
    }
}

fun FaceViewModel.processRecognizeResult(
    bitmap: Bitmap,
    onComplete: (FaceRecognizeResult) -> Unit
) {
    viewModelScope.launch {
        recognizeFaceUseCase(bitmap)
            .onSuccess { onComplete(it) }
            .onFailure {
                onComplete(FaceRecognizeResult(
                    recognized = false, name = null, confidence = null,
                    message = "Không kết nối được tới máy chủ hoặc lỗi nhận diện: ${it.message ?: "Lỗi không xác định"}"
                ))
            }
    }
}
