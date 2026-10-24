package com.robotuth.v3.data.mlkit

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage

interface FaceDetector {
    suspend fun detect(bitmap: Bitmap): Result<DetectionResult>
    fun detectAsync(bitmap: Bitmap, callback: (Result<DetectionResult>) -> Unit)
    fun detectAsync(inputImage: InputImage, callback: (Result<DetectionResult>) -> Unit)
}

data class DetectionResult(
    val faces: List<FaceData>
)

data class FaceData(
    val boundingBox: Rect,
    val confidence: Float
)
