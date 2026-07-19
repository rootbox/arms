package com.arms.androidauto.ui.nas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arms.androidauto.core.model.NasPlaylist
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import com.arms.androidauto.ui.theme.SpotifyTextMuted

// 곡을 어느 플레이리스트에 담을지 고르는 다이얼로그.
// 목록에서 고르거나, 그 자리에서 새로 만들어 바로 담을 수 있다.
@Composable
fun AddToPlaylistDialog(
    songCount: Int,
    playlists: List<NasPlaylist>,
    onDismiss: () -> Unit,
    onSelect: (playlistId: Long) -> Unit,
    onCreateAndSelect: (name: String) -> Unit
) {
    // 담을 곳이 하나도 없으면 빈 목록을 보여줄 이유가 없으니 바로 생성 모드로 연다.
    var isCreating by remember { mutableStateOf(playlists.isEmpty()) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isCreating) "새 플레이리스트" else "플레이리스트에 담기",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            if (isCreating) {
                Column {
                    Text(
                        text = "${songCount}곡을 담을 플레이리스트 이름을 정해주세요",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyTextMuted
                    )
                    Spacer(modifier = Modifier.padding(top = Spacing.sm))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("예: 드라이브용", style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SpotifyGreen,
                            unfocusedBorderColor = SpotifyTextMuted.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isCreating = true }
                                .padding(vertical = Spacing.md)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = SpotifyGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.md))
                            Text(
                                "새 플레이리스트 만들기",
                                style = MaterialTheme.typography.titleMedium,
                                color = SpotifyGreen
                            )
                        }
                    }
                    items(playlists, key = { it.id }) { playlist ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(playlist.id) }
                                .padding(vertical = Spacing.sm)
                        ) {
                            Text(
                                playlist.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${playlist.songCount}곡",
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyTextMuted
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isCreating) {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { onCreateAndSelect(newName) }
                ) {
                    Text("만들고 담기")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
