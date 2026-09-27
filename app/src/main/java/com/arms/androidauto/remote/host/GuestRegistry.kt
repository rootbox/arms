package com.arms.androidauto.remote.host

import com.arms.androidauto.core.remote.RemoteGuest

// 호스트에 "지금 붙어 있는" 게스트 목록. 브로커 연결 여부와는 별개다 — 브로커에 붙어 있어도 게스트가
// Hello를 보내기 전에는 아무도 없는 것이고, Bye 없이 사라진 게스트는 lastSeen 만료(3분)로 정리한다.
// 순수 Kotlin(서비스가 main 스레드에서만 부른다).
class GuestRegistry(private val expiryMs: Long = DEFAULT_EXPIRY_MS) {

    private val byName = LinkedHashMap<String, RemoteGuest>()

    val guests: List<RemoteGuest> get() = byName.values.toList()

    // Hello: 새 게스트면 추가, 있으면 lastSeen 갱신. 목록(이름 집합)이 바뀌었으면 true.
    fun hello(rawName: String, nowMs: Long): Boolean {
        val name = normalizeName(rawName)
        val isNew = !byName.containsKey(name)
        byName[name] = RemoteGuest(name = name, lastSeenMs = nowMs)
        return isNew
    }

    // Bye: 이름이 등록돼 있었으면 지우고 true.
    fun bye(rawName: String): Boolean = byName.remove(normalizeName(rawName)) != null

    // 만료된 게스트를 지운다. 하나라도 지웠으면 true.
    fun sweep(nowMs: Long): Boolean {
        val expired = byName.values.filter { nowMs - it.lastSeenMs >= expiryMs }.map { it.name }
        expired.forEach { byName.remove(it) }
        return expired.isNotEmpty()
    }

    fun clear() = byName.clear()

    companion object {
        const val DEFAULT_EXPIRY_MS = 3 * 60_000L
        const val FALLBACK_NAME = "리모컨"
        private const val MAX_NAME_LENGTH = 40

        // 기기 모델명 정도만 받는다. 비어 있으면 대체 이름, 너무 길면 자른다(화면·지문 안정).
        fun normalizeName(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return FALLBACK_NAME
            return if (t.length > MAX_NAME_LENGTH) t.take(MAX_NAME_LENGTH) else t
        }
    }
}

// 상단바 칩 문구. 게스트 없음 → null(호출자가 "리모컨 대기 중"을 쓴다), 1대 → "<이름> 연결됨",
// 여러 대 → "<첫 이름> 외 n대 연결됨".
fun guestPresenceLabel(guests: List<RemoteGuest>): String? {
    val first = guests.firstOrNull() ?: return null
    return if (guests.size == 1) "${first.name} 연결됨" else "${first.name} 외 ${guests.size - 1}대 연결됨"
}
