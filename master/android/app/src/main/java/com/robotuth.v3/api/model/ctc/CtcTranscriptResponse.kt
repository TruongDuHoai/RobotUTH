package com.robotuth.v3.api.model.ctc

import com.google.gson.annotations.SerializedName

/**
 * Response model cho CTC STT API
 * Endpoint: POST /asr/ctc/chunk
 */
data class CtcTranscriptResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("transcript")
    val transcript: String,

    @SerializedName("duration_seconds")
    val durationSeconds: Double,

    @SerializedName("processing_time_seconds")
    val processingTimeSeconds: Double,

    @SerializedName("real_time_factor")
    val realTimeFactor: Double,

    @SerializedName("session_id")
    val sessionId: String? = null,

    @SerializedName("model")
    val model: String? = null,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("detail")
    val detail: String? = null
)

/**
 * Request model cho CTC STT API
 */
data class CtcTranscriptRequest(
    @SerializedName("session_id")
    val sessionId: String
)

/**
 * Health check response
 */
data class CtcHealthResponse(
    @SerializedName("status")
    val status: String,

    @SerializedName("model_loaded")
    val modelLoaded: Boolean,

    @SerializedName("device")
    val device: String,

    @SerializedName("cuda_available")
    val cudaAvailable: Boolean,

    @SerializedName("chunk_duration")
    val chunkDuration: Double
)
