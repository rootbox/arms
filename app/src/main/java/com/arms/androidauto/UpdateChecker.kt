package com.arms.androidauto

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class UpdateInfo(val versionName: String, val downloadUrl: String)

// GitHub Releases를 통해 케이블/adb 없이 새 버전을 확인하고 설치할 수 있게 한다.
// 릴리스 태그는 "v<versionCode>" 형식이어야 하며(예: v3), APK 에셋이 첨부되어 있어야 한다.
class UpdateChecker(private val context: Context) {
    private val client = OkHttpClient()
    private val latestReleaseUrl = "https://api.github.com/repos/rootbox/arms/releases/latest"
    private val prefs = context.getSharedPreferences("update_checker", Context.MODE_PRIVATE)

    // 설정(정보) 화면/footer에 표시할 현재 설치된 버전.
    fun currentVersionName(): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }

    // GitHub Releases 확인에 마지막으로 성공한 시각. footer에 "마지막 업데이트 확인" 표시용.
    fun lastCheckedAtMillis(): Long? {
        val value = prefs.getLong(KEY_LAST_CHECKED_AT, -1L)
        return if (value > 0) value else null
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(latestReleaseUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SimpleRadio-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                prefs.edit().putLong(KEY_LAST_CHECKED_AT, System.currentTimeMillis()).apply()
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                val tagName = json.optString("tag_name")
                val remoteVersionCode = Regex("v(\\d+)").find(tagName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@withContext null
                if (remoteVersionCode <= currentVersionCode()) return@withContext null

                val assets = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                apkUrl?.let { url -> UpdateInfo(json.optString("name").ifBlank { tagName }, url) }
            }
        } catch (e: Exception) {
            null
        }
    }

    // APK를 캐시에 내려받고 시스템 설치 확인 다이얼로그를 띄운다.
    // 설치 자체는 Android 정책상 항상 사용자 확인이 필요하므로 완전 자동/무음 설치는 불가능하다.
    // response.body.bytes()로 전체를 메모리에 올리면 APK가 크거나 저사양 기기에서 OOM이 날 수 있으므로,
    // 디스크로 바로 스트리밍해서 쓴다.
    suspend fun downloadAndInstall(update: UpdateInfo) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(update.downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("다운로드 실패: ${response.code}")
            val body = response.body ?: throw IOException("빈 응답")

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { it.delete() }
            val file = File(updatesDir, "simple-radio-${update.versionName}.apk")
            body.byteStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.artworkprovider", file)
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    private fun currentVersionCode(): Int {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    }

    private companion object {
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
    }
}
