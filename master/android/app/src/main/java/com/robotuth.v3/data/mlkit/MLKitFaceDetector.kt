package com.robotuth.v3.data.mlkit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MLKitFaceDetector : FaceDetector {

    private val mlKitDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    override suspend fun detect(bitmap: Bitmap): Result<DetectionResult> {
        return suspendCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            mlKitDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    val faceData = faces.map { face ->
                        FaceData(
                            boundingBox = face.boundingBox,
                            confidence = face.trackingId?.toFloat() ?: 1.0f
                        )
                    }
                    continuation.resume(Result.success(DetectionResult(faces = faceData)))
                }
                .addOnFailureListener { error ->
                    continuation.resume(Result.failure(error))
                }
        }
    }

    override fun detectAsync(bitmap: Bitmap, callback: (Result<DetectionResult>) -> Unit) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        detectAsync(inputImage, callback)
    }

    override fun detectAsync(inputImage: InputImage, callback: (Result<DetectionResult>) -> Unit) {
        mlKitDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                val faceData = faces.map { face ->
                    FaceData(
                        boundingBox = face.boundingBox,
                        confidence = face.trackingId?.toFloat() ?: 1.0f
                    )
                }
                callback(Result.success(DetectionResult(faces = faceData)))
            }
            .addOnFailureListener { error ->
                callback(Result.failure(error))
            }
    }
}
