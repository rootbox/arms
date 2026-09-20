package com.arms.androidauto

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class StartPositionPolicyTest {
    @Test fun `요청 메타데이터의 인덱스와 위치가 우선한다`() {
        assertEquals(2 to 15_000L, StartPositionPolicy.resolve(C.INDEX_UNSET, C.TIME_UNSET, 2, 15_000L, 82))
    }
    @Test fun `메타데이터가 없으면 컨트롤러의 startIndex를 쓴다`() {
        assertEquals(1 to 0L, StartPositionPolicy.resolve(1, 0L, null, null, 5))
    }
    @Test fun `둘 다 없으면 0과 요청 위치(TIME_UNSET=라이브 최신)`() {
        assertEquals(0 to C.TIME_UNSET, StartPositionPolicy.resolve(C.INDEX_UNSET, C.TIME_UNSET, null, null, 1))
    }
    @Test fun `확장된 큐 크기로 안전하게 자른다`() {
        assertEquals(4 to 0L, StartPositionPolicy.resolve(C.INDEX_UNSET, 0L, 99, 0L, 5))
        assertEquals(0 to 0L, StartPositionPolicy.resolve(C.INDEX_UNSET, 0L, 3, 0L, 0))
    }
}
