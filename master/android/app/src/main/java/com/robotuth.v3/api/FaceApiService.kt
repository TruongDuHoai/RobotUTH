package com.robotuth.v3.api

import com.robotuth.v3.api.model.*
import retrofit2.Response
import retrofit2.http.*

interface FaceApiService {

    @GET("health")
    suspend fun getHealth(): Response<HealthResponse>

    @POST("api/attendance/face/detect")
    suspend fun detectFaces(@Body request: FaceDetectRequest): Response<FaceDetectResponse>

    @POST("api/attendance/face/enroll")
    suspend fun enrollFace(@Body request: FaceEnrollRequest): Response<FaceEnrollResponse>

    @POST("api/attendance/face/recognize")
    suspend fun recognizeFace(@Body request: FaceRecognizeRequest): Response<FaceRecognizeResponse>

    @GET("api/attendance/face/list")
    suspend fun listFaces(): Response<FaceListResponse>

    @GET("api/attendance/face/history")
    suspend fun getAttendanceHistory(@Query("limit") limit: Int = 100): Response<AttendanceHistoryResponse>

    @DELETE("api/attendance/face/{faceId}")
    suspend fun deleteFace(@Path("faceId") faceId: String): Response<FaceDeleteResponse>
}
