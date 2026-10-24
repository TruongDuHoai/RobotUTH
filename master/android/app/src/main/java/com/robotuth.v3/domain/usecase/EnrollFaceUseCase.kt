package com.robotuth.v3.domain.usecase

import android.graphics.Bitmap
import com.robotuth.v3.domain.model.FaceEnrollResult
import com.robotuth.v3.repository.FaceRepository
import javax.inject.Inject

interface EnrollFaceUseCase {
    suspend operator fun invoke(name: String, bitmap: Bitmap): Result<FaceEnrollResult>
}

class EnrollFaceUseCaseImpl @Inject constructor(
    private val faceRepository: FaceRepository
) : EnrollFaceUseCase {

    override suspend fun invoke(name: String, bitmap: Bitmap): Result<FaceEnrollResult> {
        return faceRepository.enrollFace(name, bitmap).map { response ->
            FaceEnrollResult(
                success = response.success,
                name = response.name,
                message = if (response.success) {
                    "Enrolled: ${response.name}"
                } else {
                    "Failed: ${response.message}"
                }
            )
        }
    }
}
