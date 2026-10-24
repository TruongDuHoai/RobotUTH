package com.robotuth.v3.api

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class TtsSynthesizeRequest(
    @SerializedName("text") val text: String,
    @SerializedName("voice") val voice: String? = null
)

interface TtsApiService {
    @POST("api/tts/synthesize")
    suspend fun synthesize(
        @Body body: TtsSynthesizeRequest
    ): Response<ResponseBody>
}
