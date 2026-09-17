package com.arms.androidauto.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.desktop.ui.RemoteImage
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope

private val Bg = Color(0xFF0E0E10)
private val Card = Color(0xFF1B1B1F)
private val Green = Color(0xFF1DB954)
private val TextMuted = Color(0xFFB3B3B3)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Simple Radio",
        state = rememberWindowState(width = 420.dp, height = 720.dp),
    ) {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        DisposableEffect(Unit) { onDispose { state.release() } }
        MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = Card)) {
            App(state)
        }
    }
}

@Composable
private fun App(state: AppState) {
    var showSettings by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        // 상단 바
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Simple Radio", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSettings = true }) { Text("NAS 설정", color = Green) }
        }
        state.statusMessage?.let {
            Text(it, color = TextMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
        // 목록
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
            item { SectionLabel("라디오") }
            items(state.stationList()) { (id, name, sub) ->
                Row2(name, sub, playing = state.currentId == id) { state.playRadio(id) }
            }
            if (state.nasConfigured) {
                item { SectionLabel("내 음악 (NAS)") }
                if (state.nasAlbums.isEmpty()) item { Text("앨범을 불러오는 중…", color = TextMuted, modifier = Modifier.padding(16.dp)) }
                items(state.nasAlbums) { album: NasAlbum ->
                    Row2(album.name, "${album.albumArtist.ifBlank { "알 수 없는 아티스트" }} · ${album.songCount}곡",
                        playing = state.currentId == "nas:${album.key}") { state.playAlbum(album) }
                }
            }
        }
        NowPlayingBar(state)
    }
    if (showSettings) NasSettingsDialog(onDismiss = { showSettings = false }) { b, a, p ->
        state.saveNasCredentials(b, a, p); showSettings = false
    }
}

@Composable private fun SectionLabel(t: String) =
    Text(t, color = TextMuted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun Row2(title: String, subtitle: String, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (playing) Green else Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (playing) Text("▶", color = Green)
    }
}

@Composable
private fun NowPlayingBar(state: AppState) {
    Surface(color = Card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2E))) {
                RemoteImage(state.nowCover, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(state.nowTitle, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(state.nowSubtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = state::previous) { Text("⏮", color = Color.White) }
            Button(onClick = state::togglePlayPause, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Text(if (state.isPlaying) "❚❚" else "▶", color = Color.Black)
            }
            TextButton(onClick = state::next) { Text("⏭", color = Color.White) }
        }
    }
}

@Composable
private fun NasSettingsDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var url by remember { mutableStateOf("https://") }
    var acc by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NAS 연결") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("주소 (예: https://내nas:5001)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(acc, { acc = it }, label = { Text("계정") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text("비밀번호") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, acc, pw) }) { Text("연결") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
