package com.arms.androidauto.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arms.androidauto.ui.theme.Sizes
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.YtRed
import com.arms.androidauto.ui.theme.YtTextSecondary

// 재생 중인 행/커버 위에 얹는 작은 이퀄라이저. YouTube Music처럼 빨강이 기본이다.
@Composable
fun EqualizerBars(
    isAnimating: Boolean,
    color: Color = YtRed,
    barCount: Int = 3,
    height: Dp = Sizes.equalizerHeight,
    barWidth: Dp = 3.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(height)
    ) {
        repeat(barCount) { index ->
            val heightFraction by if (isAnimating) {
                infiniteTransition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 420 + index * 130, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bar$index"
                )
            } else {
                remember { mutableStateOf(0.2f) }
            }
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(heightFraction)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

// "ON AIR" 라이브 태그. 재생 중이면 빨강, 아니면 회색 STANDBY.
// compact=true면 목록 행에 들어가는 작은 텍스트 태그, false면 전체화면 플레이어용 알약.
@Composable
fun OnAirTag(isOnAir: Boolean, compact: Boolean = false) {
    val color = if (isOnAir) YtRed else YtTextSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (compact) {
            Modifier
        } else {
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = Spacing.xs)
        }
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 6.dp else Spacing.sm)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(if (compact) Spacing.xs else Spacing.xs + 2.dp))
        Text(
            text = if (isOnAir) "ON AIR" else "STANDBY",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
