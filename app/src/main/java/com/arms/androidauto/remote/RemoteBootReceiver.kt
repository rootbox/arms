package com.arms.androidauto.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arms.androidauto.remote.host.RemoteHostService

// 벽걸이 태블릿(호스트)은 재부팅 뒤에도 사람이 앱을 열지 않는다. 역할이 호스트면 부팅 직후 대기 서비스를 올린다.
class RemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RemoteHostService.syncWithRole(context)
        }
    }
}
