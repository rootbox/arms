package com.arms.androidauto

import android.content.pm.ActivityInfo

// 화면 회전 정책. 예전엔 매니페스트에서 세로로 고정해 태블릿을 가로로 돌려도 2단
// 레이아웃이 나오지 않았다. 폰(sw < 600dp)은 그대로 세로 고정, 태블릿은 회전을 허용한다.
internal object OrientationPolicy {
    const val TABLET_MIN_SW_DP = 600

    fun requestedOrientation(smallestScreenWidthDp: Int): Int =
        if (smallestScreenWidthDp >= TABLET_MIN_SW_DP) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
