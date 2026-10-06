package com.arms.androidauto.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.arms.androidauto.AutoResumePolicy
import com.arms.androidauto.core.data.PlaybackStateStore
import com.arms.androidauto.remote.host.RemoteHostService

// 벽걸이 태블릿(홈 플레이어)은 재부팅이나 앱 업데이트 뒤에도 사람이 앱을 열지 않는다.
// - 역할이 호스트면 리모컨 대기 서비스를 다시 올린다.
// - 직전에 재생 중이었다면 이어서 재생한다(홈 플레이어만. 폰은 하지 않는다).
// 예전엔 부팅만 처리해 v23 업데이트 직후 호스트·라디오가 앱을 열 때까지 멈춰 있었다(2026-10-06).
class RemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val reason = if (action == Intent.ACTION_BOOT_COMPLETED) "재부팅" else "앱 업데이트"
        val pending = goAsync()
        Thread {
            try {
                RemoteHostService.syncWithRole(context)
                val isHomePlayer = runCatching { RemoteSettingsStore(context).getRole() == RemoteRole.HOST }.getOrDefault(false)
                val wasPlaying = PlaybackStateStore(context).isPlaybackIntentActive()
                if (AutoResumePolicy.shouldResumePlayback(isHomePlayer, wasPlaying)) {
                    Log.i("ARMS", "$reason 뒤 이어서 재생")
                    sendPlay(context)
                } else {
                    Log.i("ARMS", "$reason 뒤 자동 재생 안 함 (홈 플레이어=$isHomePlayer, 직전 재생=$wasPlaying)")
                }
            } catch (t: Throwable) {
                Log.w("ARMS", "$reason 뒤 복원 실패 ${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    // 차량·블루투스 PLAY와 같은 경로: Media3 MediaButtonReceiver가 서비스를 포그라운드로 띄우고
    // onPlaybackResumption이 마지막 채널(또는 NAS 앨범)을 돌려준다.
    private fun sendPlay(context: Context) {
        val now = SystemClock.uptimeMillis()
        val keyIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClassName(context, "androidx.media3.session.MediaButtonReceiver")
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        context.sendBroadcast(keyIntent)
    }
}
