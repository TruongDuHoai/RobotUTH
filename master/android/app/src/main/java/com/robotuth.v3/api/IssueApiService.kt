package com.robotuth.v3.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class IssueCreateRequest(
    @SerializedName("identity") val identity: String,
    @SerializedName("detail") val detail: String,
    @SerializedName("other_notes") val otherNotes: String = ""
)

interface IssueApiService {
    @POST("api/issues")
    suspend fun create(@Body request: IssueCreateRequest): Response<Any>
}
