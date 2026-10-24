package com.robotuth.v3.domain.usecase

import android.graphics.Bitmap
import android.util.Log
import com.robotuth.v3.domain.model.FaceRecognizeResult
import com.robotuth.v3.repository.FaceRepository
import javax.inject.Inject

interface RecognizeFaceUseCase {
    suspend operator fun invoke(bitmap: Bitmap): Result<FaceRecognizeResult>
}

class RecognizeFaceUseCaseImpl @Inject constructor(
    private val faceRepository: FaceRepository
) : RecognizeFaceUseCase {

    override suspend fun invoke(bitmap: Bitmap): Result<FaceRecognizeResult> {
        return try {
            faceRepository.recognizeFace(bitmap).map { response ->
                FaceRecognizeResult(
                    recognized = response.recognized,
                    name = response.name,
                    confidence = response.confidence,
                    message = if (response.recognized) {
                        "Nhận ra: ${response.name}"
                    } else {
                        response.message ?: "Không nhận ra"
                    }
                )
            }
        } catch (e: Exception) {
            Log.w("RecognizeFaceUseCase", "Recognition failed: ${e.message}")
            Result.failure(e)
        }
    }
}
