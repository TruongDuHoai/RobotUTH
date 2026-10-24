package com.robotuth.v3.api

import com.robotuth.v3.config.AppConfig
import com.robotuth.v3.utils.UrlHelper
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    @Volatile private var currentBaseUrl = AppConfig.DEFAULT_API_BASE_URL

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
        redactHeader("Authorization")
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                if (AppConfig.ROBOT_API_TOKEN.isNotBlank()) {
                    header("Authorization", "Bearer ${AppConfig.ROBOT_API_TOKEN}")
                }
            }.build()
            chain.proceed(request)
        }
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofitCache = mutableMapOf<String, Retrofit>()

    @Synchronized
    fun setBaseUrl(url: String) {
        currentBaseUrl = UrlHelper.ensureTrailingSlash(url)
    }

    fun getBaseUrl(): String = currentBaseUrl

    fun getRetrofit(baseUrl: String = currentBaseUrl): Retrofit {
        val normalizedUrl = UrlHelper.ensureTrailingSlash(baseUrl)
        return synchronized(retrofitCache) {
            retrofitCache.getOrPut(normalizedUrl) {
                Retrofit.Builder()
                    .baseUrl(normalizedUrl)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            }
        }
    }

    fun getFaceApiService(baseUrl: String = currentBaseUrl): FaceApiService =
        getRetrofit(baseUrl).create(FaceApiService::class.java)

    fun getChatApiService(baseUrl: String = currentBaseUrl): ChatApiService =
        getRetrofit(baseUrl).create(ChatApiService::class.java)

    fun getTtsApiService(baseUrl: String = currentBaseUrl): TtsApiService =
        getRetrofit(baseUrl).create(TtsApiService::class.java)

    fun getIssueApiService(baseUrl: String = currentBaseUrl): IssueApiService =
        getRetrofit(baseUrl).create(IssueApiService::class.java)
}
