package com.robotuth.v3.api

import com.robotuth.v3.api.model.ChatResponse
import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Query

interface ChatApiService {
    @POST("api/chat/query")
    suspend fun queryChat(
        @Query("query") query: String
    ): Response<ChatResponse>
}
