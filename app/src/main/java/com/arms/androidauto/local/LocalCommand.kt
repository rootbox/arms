package com.arms.androidauto.local

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

// POST /api/v1/command 의 명령(smartthings/LAN_API.md "명령" 표).
sealed class LocalCommand {
    data object On : LocalCommand()
    data object Off : LocalCommand()
    data object Play : LocalCommand()
    data object Pause : LocalCommand()
    data object Stop : LocalCommand()
    data object Next : LocalCommand()
    data object Previous : LocalCommand()
    data class SetVolume(val percent: Int) : LocalCommand()
    data object VolumeUp : LocalCommand()
    data object VolumeDown : LocalCommand()
    data object Mute : LocalCommand()
    data object Unmute : LocalCommand()
    data class PlayPreset(val id: String) : LocalCommand()
}

// 실행 결과. httpStatus: 200 성공, 400 알 수 없는 값(예: 없는 프리셋), 503 세션 미연결, 500 그 밖의 실패.
data class CommandOutcome(val ok: Boolean, val message: String? = null, val httpStatus: Int = if (ok) 200 else 500) {
    companion object {
        val OK = CommandOutcome(true)
        fun badRequest(message: String) = CommandOutcome(false, message, 400)
        fun unavailable(message: String) = CommandOutcome(false, message, 503)
        fun failed(message: String) = CommandOutcome(false, message, 500)
    }
}

object LocalCommandParser {
    sealed class Result {
        data class Ok(val command: LocalCommand) : Result()
        data class Invalid(val message: String) : Result()
    }

    private const val MAX_PRESET_ID = 64

    fun parse(body: String): Result {
        val obj = try {
            JSONTokener(body).nextValue() as? JSONObject
        } catch (e: JSONException) {
            null
        } ?: return Result.Invalid("invalid json")
        val name = obj.opt("command") as? String ?: return Result.Invalid("missing command")
        val value: Any? = obj.opt("value")?.takeIf { it != JSONObject.NULL }
        val cmd = when (name) {
            "on" -> LocalCommand.On
            "off" -> LocalCommand.Off
            "play" -> LocalCommand.Play
            "pause" -> LocalCommand.Pause
            "stop" -> LocalCommand.Stop
            "next" -> LocalCommand.Next
            "previous" -> LocalCommand.Previous
            "volumeUp" -> LocalCommand.VolumeUp
            "volumeDown" -> LocalCommand.VolumeDown
            "mute" -> LocalCommand.Mute
            "unmute" -> LocalCommand.Unmute
            "setVolume" -> {
                val v = volumeOf(value) ?: return Result.Invalid("setVolume value must be a number 0..100")
                LocalCommand.SetVolume(v)
            }
            "playPreset" -> {
                val id = presetIdOf(value) ?: return Result.Invalid("playPreset value must be a preset id")
                LocalCommand.PlayPreset(id)
            }
            else -> return Result.Invalid("unknown command")
        }
        return Result.Ok(cmd)
    }

    // 정수·실수(반올림)·숫자 문자열을 받는다. 0..100 밖이면 거부(잘라내지 않는다).
    private fun volumeOf(value: Any?): Int? {
        val d: Double = when (value) {
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: return null
            else -> return null
        }
        if (d.isNaN() || d.isInfinite() || d < 0.0 || d > 100.0) return null
        return d.roundToInt()
    }

    private fun presetIdOf(value: Any?): String? {
        val s = when (value) {
            is String -> value.trim()
            is Int, is Long -> value.toString()
            is Number -> value.toDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else return null }
            else -> return null
        }
        return s.takeIf { it.isNotEmpty() && it.length <= MAX_PRESET_ID }
    }
}
