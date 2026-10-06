package com.arms.androidauto.core.remote

import java.net.URI

// 브로커 주소 사전 검사. 2026-10-06 태블릿 사고: 개발용 주소(ws://127.0.0.1:9001, adb reverse 전용)가
// 실사용 호스트에 저장돼 QR로 폰에까지 전달됐다. 127.0.0.1은 각 기기 "자기 자신"이라 두 기기가 절대
// 만날 수 없는데도 앱은 아무 경고 없이 저장·QR 생성·"리모컨 대기 중" 표시를 했다. 저장/스캔 시점에 막는다.
object BrokerUrlPolicy {
    enum class Problem {
        EMPTY,
        BAD_FORMAT,
        UNSUPPORTED_SCHEME,
        // localhost / 127.0.0.0/8 / ::1 / 0.0.0.0 — 기기 자기 자신을 가리킨다.
        LOOPBACK,
        // ws:// · tcp:// 평문을 공인 주소에 쓰려 함(브로커 계정이 평문으로 나간다). 사설 LAN 주소는 허용.
        PLAINTEXT_PUBLIC,
    }

    sealed class Result {
        data class Ok(val normalizedUrl: String, val host: String, val isLan: Boolean) : Result()
        data class Invalid(val problem: Problem) : Result()
    }

    // allowLoopback: debug 빌드에서 adb reverse 개발을 위해서만 true.
    fun check(url: String, allowLoopback: Boolean = false): Result {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed == "wss://" || trimmed == "ws://") return Result.Invalid(Problem.EMPTY)
        val uri = try { URI(trimmed) } catch (_: Exception) { return Result.Invalid(Problem.BAD_FORMAT) }
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid(Problem.BAD_FORMAT)
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return Result.Invalid(Problem.BAD_FORMAT)
        val tls = when (scheme) {
            "wss", "ssl", "mqtts" -> true
            "ws", "tcp", "mqtt" -> false
            else -> return Result.Invalid(Problem.UNSUPPORTED_SCHEME)
        }
        if (isLoopback(host) && !allowLoopback) return Result.Invalid(Problem.LOOPBACK)
        val lan = isLoopback(host) || isPrivateLan(host)
        if (!tls && !lan) return Result.Invalid(Problem.PLAINTEXT_PUBLIC)
        return Result.Ok(normalizedUrl = trimmed, host = host, isLan = lan)
    }

    fun message(problem: Problem): String = when (problem) {
        Problem.EMPTY -> "브로커 주소를 입력하세요 (예: wss://mqtt.example.com/)."
        Problem.BAD_FORMAT -> "브로커 주소 형식이 올바르지 않습니다 (예: wss://mqtt.example.com/)."
        Problem.UNSUPPORTED_SCHEME -> "wss:// 로 시작하는 주소를 쓰세요."
        Problem.LOOPBACK -> "127.0.0.1·localhost는 이 기기 자신을 가리켜 다른 기기와 연결될 수 없습니다. " +
            "NAS 브로커 주소(wss://…)를 입력하세요."
        Problem.PLAINTEXT_PUBLIC -> "공용 주소에는 암호화된 wss:// 를 쓰세요 (ws:// 는 같은 와이파이의 사설 주소에서만 허용)."
    }

    internal fun isLoopback(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "0:0:0:0:0:0:0:1" ||
            host == "0.0.0.0" || host.startsWith("127.")

    internal fun isPrivateLan(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return host.endsWith(".local")
        val o = parts.map { it.toIntOrNull() ?: return false }
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)
    }
}
