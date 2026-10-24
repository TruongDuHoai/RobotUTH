package com.robotuth.v3.api.model

import com.google.gson.annotations.SerializedName

data class ChatResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("response") val response: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("contexts") val contexts: List<String>?,
    @SerializedName("mode") val mode: String?
)
