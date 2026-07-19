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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arms.androidauto.R
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.model.NasSong
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.Radius
import com.arms.androidauto.ui.theme.Sizes
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import com.arms.androidauto.ui.theme.SpotifySurfaceElevated
import com.arms.androidauto.ui.theme.SpotifyTextMuted

// 앨범 안의 곡 목록. 앨범을 눌렀을 때 바로 재생하는 대신 이 화면으로 들어와서,
// 전체 재생 / 특정 곡부터 재생 / 곡을 플레이리스트에 담기를 고를 수 있게 한다.
@Composable
fun NasAlbumDetailScreen(
    album: NasAlbum,
    songs: List<NasSong>,
    isLoading: Boolean,
    isPlayingThisAlbum: Boolean,
    playingTrackTitle: String?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onAddSongToPlaylist: (NasSong) -> Unit,
    onAddAllToPlaylist: () -> Unit
) {
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
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            contentPadding = PaddingValues(bottom = Spacing.xl)
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(SpotifySurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_album),
                                contentDescription = null,
                                tint = SpotifyTextMuted,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.lg))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = album.name,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            Text(
                                text = "${album.albumArtist.ifBlank { "알 수 없는 아티스트" }} · ${album.songCount}곡",
                                style = MaterialTheme.typography.bodySmall,
                                color = SpotifyTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onPlayAll,
                            enabled = songs.isNotEmpty(),
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
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        TextButton(onClick = onAddAllToPlaylist, enabled = songs.isNotEmpty()) {
                            Text(
                                "전체 담기",
                                style = MaterialTheme.typography.labelLarge,
                                color = SpotifyTextMuted
                            )
                        }
                    }
                }
            }

            if (isLoading && songs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SpotifyGreen)
                    }
                }
            }

            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                NasSongRow(
                    trackNumber = index + 1,
                    title = song.title,
                    subtitle = song.artist ?: album.albumArtist,
                    // 이 앨범을 재생 중이면서 제목이 같은 곡을 "지금 재생 중"으로 본다.
                    isPlaying = isPlayingThisAlbum && playingTrackTitle == song.title,
                    trailingIcon = Icons.Filled.Add,
                    trailingDescription = "플레이리스트에 담기",
                    onClick = { onPlayFrom(index) },
                    onTrailingClick = { onAddSongToPlaylist(song) }
                )
            }
        }
    }
}

// 앨범 상세와 플레이리스트 상세가 함께 쓰는 곡 행.
@Composable
fun NasSongRow(
    trackNumber: Int,
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    trailingDescription: String,
    onClick: () -> Unit,
    onTrailingClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
    ) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isPlaying) SpotifyGreen else SpotifyTextMuted
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onTrailingClick) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = trailingDescription,
                tint = SpotifyTextMuted
            )
        }
    }
}
