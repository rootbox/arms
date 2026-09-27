package com.arms.androidauto.core.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object NetworkClient {
    // 요청/응답 바디 로그. 기본은 꺼짐 — 실사용 로그(2026-09-27 S22)에서 앱 로그의 2/3가 편성 API
    // 응답 본문이었다(릴리즈 빌드에서도 BODY 로깅이 켜져 있었음). 앱은 debug 빌드에서만 켠다.
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.NONE
    }

    var httpLoggingEnabled: Boolean
        get() = loggingInterceptor.level != HttpLoggingInterceptor.Level.NONE
        set(value) {
            loggingInterceptor.level =
                if (value) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    val radioApiService: RadioApiService = RadioApiServiceImpl(okHttpClient)
    val synologyMusicApi: SynologyMusicApi = SynologyMusicApiImpl(okHttpClient)
}
