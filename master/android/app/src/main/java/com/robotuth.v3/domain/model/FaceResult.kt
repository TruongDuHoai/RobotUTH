package com.robotuth.v3.domain.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface FaceResult {
    data class Success(
        val name: String? = null,
        val message: String
    ) : FaceResult

    data class Error(
        val message: String
    ) : FaceResult

    data object Loading : FaceResult
    data object Idle : FaceResult
}

@Immutable
data class FaceDetectionResult(
    val facesDetected: Int,
    val hasMultipleFaces: Boolean,
    val message: String
)

@Immutable
data class FaceEnrollResult(
    val success: Boolean,
    val name: String?,
    val message: String
)

@Immutable
data class FaceRecognizeResult(
    val recognized: Boolean,
    val name: String?,
    val confidence: Float?,
    val message: String
)
