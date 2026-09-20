package com.arms.androidauto.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val cache = ConcurrentHashMap<String, ImageBitmap>()
private object RemoteImageMarker

private fun fetch(url: String): ImageBitmap? {
    return try {
        val bytes: ByteArray? = if (url.startsWith("res:/")) {
            // "res:/이름.png"는 앱에 번들된 리소스(채널 아트)
            RemoteImageMarker::class.java.getResourceAsStream("/" + url.removePrefix("res:/"))?.use { it.readBytes() }
        } else {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.instanceFollowRedirects = true
            c.inputStream.use { it.readBytes() }
        }
        if (bytes == null || bytes.isEmpty()) null else SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) { null }
}

// 커버 URL을 백그라운드에서 받아 그린다. 실패/로딩 중에는 회색 배경 + 플레이스홀더.
// (안드로이드의 Coil AsyncImage 역할. 데스크톱은 skia 디코딩으로 의존성 없이 처리)
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf(url?.let { cache[it] }) }
    LaunchedEffect(url) {
        if (url == null || bitmap != null) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) { fetch(url) }
        if (bmp != null) { cache[url] = bmp; bitmap = bmp }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
