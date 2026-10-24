package com.robotuth.v3.api.model

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: String?,
    @SerializedName("model_loaded") val modelLoaded: Boolean?
)

data class FaceDetectRequest(
    @SerializedName("image_base64") val imageBase64: String
)

data class FaceBox(
    @SerializedName("x1") val x1: Int,
    @SerializedName("y1") val y1: Int,
    @SerializedName("x2") val x2: Int,
    @SerializedName("y2") val y2: Int,
    @SerializedName("confidence") val confidence: Float
)

data class FaceDetectResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("faces") val faces: List<FaceBox>?,
    @SerializedName("count") val count: Int
)

data class FaceEnrollRequest(
    @SerializedName("name") val name: String,
    @SerializedName("image_base64") val imageBase64: String,
    @SerializedName("user_id") val userId: String? = null
)

data class FaceEnrollResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("face_id") val faceId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("message") val message: String?
)

data class FaceRecognizeRequest(
    @SerializedName("image_base64") val imageBase64: String
)

data class FaceRecognizeResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("recognized") val recognized: Boolean,
    @SerializedName("name") val name: String?,
    @SerializedName("face_id") val faceId: String?,
    @SerializedName("confidence") val confidence: Float?,
    @SerializedName("message") val message: String?
)

data class FaceInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("user_id") val userId: String?,
    @SerializedName("quality_score") val qualityScore: Float?,
    @SerializedName("is_active") val isActive: Boolean?,
    @SerializedName("created_at") val createdAt: String?
)

data class FaceListResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("faces") val faces: List<FaceInfo>?,
    @SerializedName("count") val count: Int
)

data class FaceDeleteResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?
)

data class AttendanceRecord(
    @SerializedName("record_id") val recordId: String,
    @SerializedName("face_id") val faceId: String,
    @SerializedName("name") val name: String,
    @SerializedName("user_id") val userId: String?,
    @SerializedName("checkin_time") val checkinTime: String
)

data class AttendanceHistoryResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("history") val history: List<AttendanceRecord>,
    @SerializedName("count") val count: Int
)
