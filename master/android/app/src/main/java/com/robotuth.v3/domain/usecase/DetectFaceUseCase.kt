package com.robotuth.v3.domain.usecase

import android.graphics.Bitmap
import com.robotuth.v3.domain.model.FaceDetectionResult
import com.robotuth.v3.repository.FaceRepository
import javax.inject.Inject

interface DetectFaceUseCase {
    suspend operator fun invoke(bitmap: Bitmap): Result<FaceDetectionResult>
}

class DetectFaceUseCaseImpl @Inject constructor(
    private val faceRepository: FaceRepository
) : DetectFaceUseCase {

    override suspend fun invoke(bitmap: Bitmap): Result<FaceDetectionResult> {
        return faceRepository.detectFaces(bitmap).map { response ->
            val facesCount = response.count
            FaceDetectionResult(
                facesDetected = facesCount,
                hasMultipleFaces = facesCount > 1,
                message = when {
                    facesCount == 0 -> "No face detected"
                    facesCount > 1 -> "Multiple faces: $facesCount"
                    else -> "Face detected"
                }
            )
        }
    }
}
