package com.arms.androidauto.ui.nas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arms.androidauto.R
import com.arms.androidauto.core.model.NasPlaylist
import com.arms.androidauto.core.model.NasPlaylistTrack
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.Radius
import com.arms.androidauto.ui.theme.Sizes
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import com.arms.androidauto.ui.theme.SpotifySurfaceElevated
import com.arms.androidauto.ui.theme.SpotifyTextMuted

@Composable
fun NasPlaylistListContent(
    playlists: List<NasPlaylist>,
    playingPlaylistId: Long?,
    onPlaylistClick: (Long) -> Unit
) {
    if (playlists.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_library_music),
                contentDescription = null,
                tint = SpotifyTextMuted,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                "아직 플레이리스트가 없어요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                "앨범에서 곡 옆의 + 를 눌러\n원하는 곡을 담아보세요",
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyTextMuted,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl)
    ) {
        items(playlists, key = { it.id }) { playlist ->
            val isPlaying = playlist.id == playingPlaylistId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .clickable { onPlaylistClick(playlist.id) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(Sizes.listThumbnail)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(SpotifySurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_library_music),
                        contentDescription = null,
                        tint = if (isPlaying) SpotifyGreen else SpotifyTextMuted,
                        modifier = Modifier.size(Sizes.miniPlayerIcon)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isPlaying) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
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
}

@Composable
fun NasPlaylistDetailScreen(
    playlist: NasPlaylist?,
    tracks: List<NasPlaylistTrack>,
    isPlayingThisPlaylist: Boolean,
    playingTrackTitle: String?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onRemoveTrack: (NasPlaylistTrack) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = playlist?.name ?: "플레이리스트",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "더보기",
                        tint = SpotifyTextMuted
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("이름 변경") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            showRenameDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("삭제") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            showDeleteConfirm = true
                        }
                    )
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            contentPadding = PaddingValues(bottom = Spacing.xl)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = Spacing.md)
                ) {
                    Button(
                        onClick = onPlayAll,
                        enabled = tracks.isNotEmpty(),
                        shape = RoundedCornerShape(Radius.xxl),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen,
                            contentColor = RadioBgDeep
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(Sizes.miniPlayerIcon)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("모두 재생", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Text(
                        "${tracks.size}곡",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyTextMuted
                    )
                }
            }

            itemsIndexed(tracks, key = { _, track -> track.songId }) { index, track ->
                NasSongRow(
                    trackNumber = index + 1,
                    title = track.title,
                    subtitle = track.artist ?: track.albumArtist,
                    isPlaying = isPlayingThisPlaylist && playingTrackTitle == track.title,
                    trailingIcon = Icons.Filled.Close,
                    trailingDescription = "플레이리스트에서 빼기",
                    onClick = { onPlayFrom(index) },
                    onTrailingClick = { onRemoveTrack(track) }
                )
            }
        }
    }

    if (showRenameDialog && playlist != null) {
        var name by remember(playlist.id) { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("이름 변경", style = MaterialTheme.typography.titleLarge) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpotifyGreen,
                        unfocusedBorderColor = SpotifyTextMuted.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRename(name)
                        showRenameDialog = false
                    }
                ) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("취소") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("플레이리스트 삭제", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "'${playlist?.name ?: ""}'을(를) 삭제할까요? 담긴 곡 목록도 함께 사라집니다. " +
                        "NAS의 실제 음악 파일은 지워지지 않습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("취소") }
            }
        )
    }
}
