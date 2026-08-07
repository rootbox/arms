package com.arms.androidauto.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 세션 만료를 "재로그인이 필요한 오류"로 정확히 골라내야, 앱 실행마다 새로 로그인하지 않으면서도
// 죽은 세션은 스스로 복구할 수 있다. 코드를 잘못 분류하면 둘 중 하나가 깨진다.
class SynologyAuthErrorTest {

    @Test
    fun `세션 관련 오류는 재로그인 대상이다`() {
        // 105 권한 없음 / 106 세션 만료 / 107 중복 로그인으로 끊김 / 119 sid 없음
        listOf(105, 106, 107, 119).forEach { code ->
            assertTrue(isSynologyAuthError(code), "code=$code 는 인증 오류여야 한다")
        }
    }

    @Test
    fun `그 밖의 오류에는 재로그인하지 않는다`() {
        // 100 알 수 없는 오류 / 102 API 없음 / 400 잘못된 파라미터 / -1 파싱 실패
        listOf(100, 102, 103, 400, 0, -1).forEach { code ->
            assertFalse(isSynologyAuthError(code), "code=$code 는 인증 오류가 아니어야 한다")
        }
    }
}
