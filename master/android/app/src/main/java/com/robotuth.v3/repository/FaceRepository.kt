package com.robotuth.v3.repository

import android.graphics.Bitmap
import android.util.Base64
import com.robotuth.v3.api.RetrofitClient
import com.robotuth.v3.api.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class FaceRepository {

    private val apiService
        get() = RetrofitClient.getFaceApiService()

    suspend fun getHealth(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getHealth()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Health check failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun detectFaces(bitmap: Bitmap): Result<FaceDetectResponse> = withContext(Dispatchers.IO) {
        try {
            val imageBase64 = bitmapToBase64(bitmap)
            val request = FaceDetectRequest(imageBase64)
            val response = apiService.detectFaces(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Detection failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun enrollFace(name: String, bitmap: Bitmap, userId: String? = null): Result<FaceEnrollResponse> = withContext(Dispatchers.IO) {
        try {
            val imageBase64 = bitmapToBase64(bitmap)
            android.util.Log.d("FaceRepo", "Image base64 length: ${imageBase64.length}")
            val request = FaceEnrollRequest(name, imageBase64, userId)
            val response = apiService.enrollFace(request)
            val errorBodyString = response.errorBody()?.string()
            android.util.Log.d("FaceRepo", "Response code: ${response.code()}, body: $errorBodyString")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorMsg = errorBodyString ?: "Unknown error"
                Result.failure(Exception("Enrollment failed: ${response.code()} - $errorMsg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recognizeFace(bitmap: Bitmap): Result<FaceRecognizeResponse> = withContext(Dispatchers.IO) {
        try {
            val imageBase64 = bitmapToBase64(bitmap)
            android.util.Log.d(
                "FaceRepo",
                "Recognize image=${bitmap.width}x${bitmap.height}, base64=${imageBase64.length}"
            )
            val request = FaceRecognizeRequest(imageBase64)
            val response = apiService.recognizeFace(request)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                body.also {
                    android.util.Log.d(
                        "FaceRepo",
                        "Recognize response recognized=${it.recognized}, name=${it.name}, confidence=${it.confidence}, message=${it.message}"
                    )
                }
                Result.success(body)
            } else {
                val error = response.errorBody()?.string()
                android.util.Log.e("FaceRepo", "Recognize HTTP ${response.code()}: $error")
                Result.failure(Exception("Recognition failed: ${response.code()} - ${error.orEmpty()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFaces(): Result<FaceListResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.listFaces()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("List faces failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAttendanceHistory(limit: Int = 100): Result<AttendanceHistoryResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getAttendanceHistory(limit)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Get attendance history failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFace(faceId: String): Result<FaceDeleteResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.deleteFace(faceId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Delete failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    fun setServerUrl(url: String) {
        RetrofitClient.setBaseUrl(url)
    }

    fun getServerUrl(): String = RetrofitClient.getBaseUrl()
}
