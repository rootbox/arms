package com.arms.androidauto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothModePolicyTest {
    @Test fun `차량이 시작한 재생만 Android Auto 해제 시 정지한다`() {
        assertTrue(BluetoothModePolicy.shouldStopOnCarDisconnect(initiatedByCar = true))
        assertFalse(BluetoothModePolicy.shouldStopOnCarDisconnect(initiatedByCar = false))
    }

    @Test fun `포커스 재개 포기는 차량 시작 재생이면서 차가 확실히 없을 때만`() {
        assertTrue(BluetoothModePolicy.shouldSkipFocusResume(initiatedByCar = true, carDefinitelyDisconnected = true))
        assertFalse(BluetoothModePolicy.shouldSkipFocusResume(initiatedByCar = true, carDefinitelyDisconnected = false))
        assertFalse(BluetoothModePolicy.shouldSkipFocusResume(initiatedByCar = false, carDefinitelyDisconnected = true))
    }

    @Test fun `출력 경로 소실 시 라디오는 완전 정지, NAS는 일시정지 유지`() {
        assertTrue(BluetoothModePolicy.shouldStopOnBecomingNoisy(isNasContent = false))
        assertFalse(BluetoothModePolicy.shouldStopOnBecomingNoisy(isNasContent = true))
    }
}
