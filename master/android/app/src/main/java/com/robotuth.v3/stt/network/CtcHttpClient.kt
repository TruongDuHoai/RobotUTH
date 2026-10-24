package com.robotuth.v3.stt.network

import android.util.Log
import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.utils.UrlHelper
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client gửi WAV chunk lên CTC Parakeet server.
 * Endpoint: POST {serverUrl}/asr/ctc/chunk
 * Response: {"success": true, "transcript": "...", "duration_seconds": ..., "processing_time_seconds": ...}
 */
class CtcHttpClient {

    companion object { private const val TAG = "CtcHttpClient" }

    var serverUrl: String = AppConfig.DEFAULT_API_BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Gửi WAV chunk lên server.
     * @return JSONObject hoặc null nếu lỗi fatal
     */
    fun sendChunk(file: File, sessionId: String): JSONObject? {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart(
                    "audio", "chunk_${System.currentTimeMillis()}.wav",
                    file.asRequestBody("audio/wav".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/asr/ctc/chunk")
                .post(body)
                .apply {
                    if (AppConfig.ROBOT_API_TOKEN.isNotBlank()) {
                        header("Authorization", "Bearer ${AppConfig.ROBOT_API_TOKEN}")
                    }
                }
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string()
                Log.d(TAG, "HTTP ${resp.code}: ${raw?.take(200)}")
                if (resp.isSuccessful && raw != null) {
                    JSONObject(raw)
                } else {
                    val detail = parseDetail(raw) ?: "HTTP ${resp.code}"
                    // Map common server failures to clear Vietnamese messages
                    val msg = when {
                        detail.contains("ASR backend unavailable", ignoreCase = true) ||
                            detail.contains("connection attempts failed", ignoreCase = true) ->
                            "ASR GPU tắt/không kết nối (port 8002). Bật robotuth-asr trên server."
                        resp.code == 503 -> "ASR không sẵn sàng (503): $detail"
                        else -> "HTTP ${resp.code}: $detail"
                    }
                    error(msg)
                }
            }

        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Không kết nối $serverUrl", e)
            error("Không kết nối được server $serverUrl")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout", e)
            error("Server timeout khi gửi audio ASR")
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error", e)
            error(e.message ?: "Network error")
        }
    }

    private fun parseDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw).optString("detail").takeIf { it.isNotBlank() } ?: raw.take(120)
        } catch (_: Exception) {
            raw.take(120)
        }
    }

    private fun error(detail: String) = JSONObject().apply {
        put("success", false); put("detail", detail)
    }
}
