package com.arms.androidauto

import android.app.Application
import com.arms.androidauto.core.network.NetworkClient

class SimpleRadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // HTTP 바디 로그는 debug 빌드에서만. 릴리즈에서는 편성 API 응답(19KB/8초)이 logcat을 채웠다.
        NetworkClient.httpLoggingEnabled = BuildConfig.DEBUG
    }
}
