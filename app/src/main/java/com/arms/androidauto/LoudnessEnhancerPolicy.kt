package com.arms.androidauto

// SBS 라우드니스 보정(LoudnessEnhancer) 인스턴스 관리 규칙. 이펙트는 오디오 세션 ID에 묶이므로 세션이 바뀌면
// 새로 만들어야 하고, 예외가 난 인스턴스는 죽은 채로 남아 다음 적용까지 침묵 실패하므로 버리고 다시 만든다.
internal object LoudnessEnhancerPolicy {
    enum class Action { SKIP, RELEASE, REUSE, RECREATE }

    // hasEnhancer: 살아 있는 인스턴스가 있는가. boundSessionId: 그 인스턴스가 붙은 세션. currentSessionId: 플레이어의 현재 세션.
    fun decide(
        wantsBoost: Boolean,
        hasEnhancer: Boolean,
        boundSessionId: Int,
        currentSessionId: Int,
        unsetSessionId: Int
    ): Action = when {
        !wantsBoost -> if (hasEnhancer) Action.RELEASE else Action.SKIP
        currentSessionId == unsetSessionId -> Action.SKIP
        !hasEnhancer || boundSessionId != currentSessionId -> Action.RECREATE
        else -> Action.REUSE
    }

    // 예외 로그는 예외 클래스별로 한 번만. 같은 기기에서 같은 이유로 8초마다 반복되는 로그를 막는다.
    fun shouldLogError(loggedClasses: MutableSet<String>, exceptionClass: String): Boolean = loggedClasses.add(exceptionClass)
}
