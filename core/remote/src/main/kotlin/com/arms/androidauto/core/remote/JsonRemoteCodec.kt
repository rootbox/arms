package com.arms.androidauto.core.remote

import org.json.JSONArray
import org.json.JSONObject

// 메시지 ↔ JSON(평문, 봉인 전). 봉투: {"v":1,"t":"cmd"|"state"|"ack","seq":Long,"ts":Long, ...}
// - cmd:   "cmd":"play|pause|stop|next|prev|refresh|bt_reconnect|select|volume|hello|bye|st_pair",
//          "mediaId"?(select), "percent"?(volume), "guestName"?(hello)
// - state: HostState 필드 그대로("bluetooth" 객체, "items"·"guests" 배열, "stPairingOpenUntilMs"·"stClientCount"는 기본값이면 생략)
// - ack:   "ackSeq","ok","message"?
// 화이트리스트: 허용된 키 외에 다른 최상위(또는 중첩 객체) 키가 하나라도 있으면 decode는 null.
// 스키마 밖의 정보는 여기서 걸러지므로 게스트/호스트 어느 쪽도 "제어 외의 정보"를 받아들일 수 없다.
object JsonRemoteCodec : RemoteCodec {
    private const val T_CMD = "cmd"
    private const val T_STATE = "state"
    private const val T_ACK = "ack"

    private val envelopeKeys = setOf("v", "t", "seq", "ts")
    private val cmdKeys = envelopeKeys + setOf("cmd", "mediaId", "percent", "guestName")
    private val stateKeys = envelopeKeys + setOf(
        "mediaId", "title", "artist", "isPlaying", "playbackState", "artworkRef",
        "bluetooth", "batteryPercent", "updatedAtMs", "items", "volumePercent", "guests", "revoked",
        "stPairingOpenUntilMs", "stClientCount",
    )
    private val guestKeys = setOf("name", "lastSeenMs")
    private val bluetoothKeys = setOf("connected", "deviceName", "changedAtMs")
    private val itemKeys = setOf("mediaId", "name", "subtitle")
    private val ackKeys = envelopeKeys + setOf("ackSeq", "ok", "message")
    // 호스트의 LocalAuth.MAX_CLIENTS(8)보다 넉넉한 상한. 그 이상은 스키마 밖의 값으로 본다.
    private const val MAX_ST_CLIENTS = 64

    private const val C_PLAY = "play"
    private const val C_PAUSE = "pause"
    private const val C_STOP = "stop"
    private const val C_NEXT = "next"
    private const val C_PREV = "prev"
    private const val C_REFRESH = "refresh"
    private const val C_BT_RECONNECT = "bt_reconnect"
    private const val C_SELECT = "select"
    private const val C_VOLUME = "volume"
    private const val C_HELLO = "hello"
    private const val C_BYE = "bye"
    private const val C_ST_PAIR = "st_pair"

    override fun encode(message: RemoteMessage): String {
        val json = JSONObject()
            .put("v", REMOTE_PROTOCOL_VERSION)
            .put("seq", message.seq)
            .put("ts", message.sentAtMs)
        when (message) {
            is RemoteMessage.Command -> {
                json.put("t", T_CMD)
                when (val c = message.command) {
                    RemoteCommand.Play -> json.put("cmd", C_PLAY)
                    RemoteCommand.Pause -> json.put("cmd", C_PAUSE)
                    RemoteCommand.Stop -> json.put("cmd", C_STOP)
                    RemoteCommand.Next -> json.put("cmd", C_NEXT)
                    RemoteCommand.Previous -> json.put("cmd", C_PREV)
                    RemoteCommand.Refresh -> json.put("cmd", C_REFRESH)
                    RemoteCommand.BtReconnect -> json.put("cmd", C_BT_RECONNECT)
                    is RemoteCommand.Select -> json.put("cmd", C_SELECT).put("mediaId", c.mediaId)
                    is RemoteCommand.Volume -> json.put("cmd", C_VOLUME).put("percent", c.percent)
                    is RemoteCommand.Hello -> json.put("cmd", C_HELLO).put("guestName", c.guestName)
                    RemoteCommand.Bye -> json.put("cmd", C_BYE)
                    RemoteCommand.OpenSmartThingsPairing -> json.put("cmd", C_ST_PAIR)
                }
            }
            is RemoteMessage.State -> {
                json.put("t", T_STATE)
                val s = message.state
                // JSONObject.put(key, null)은 키를 지우므로 null 필드는 자연스럽게 생략된다.
                json.put("mediaId", s.mediaId)
                json.put("title", s.title)
                json.put("artist", s.artist)
                json.put("isPlaying", s.isPlaying)
                json.put("playbackState", s.playbackState)
                json.put("artworkRef", s.artworkRef)
                s.bluetooth?.let { bt ->
                    json.put(
                        "bluetooth",
                        JSONObject()
                            .put("connected", bt.connected)
                            .put("deviceName", bt.deviceName)
                            .put("changedAtMs", bt.changedAtMs),
                    )
                }
                json.put("batteryPercent", s.batteryPercent)
                json.put("updatedAtMs", s.updatedAtMs)
                json.put("volumePercent", s.volumePercent)
                if (s.guests.isNotEmpty()) json.put(
                    "guests",
                    JSONArray().also { arr ->
                        s.guests.forEach { g -> arr.put(JSONObject().put("name", g.name).put("lastSeenMs", g.lastSeenMs)) }
                    },
                )
                if (s.revoked) json.put("revoked", true)
                // 닫혀 있음(null)·0대는 키를 생략한다(구 게스트 호환 — 화이트리스트 밖 키는 거부되므로).
                json.put("stPairingOpenUntilMs", s.stPairingOpenUntilMs)
                if (s.stClientCount > 0) json.put("stClientCount", s.stClientCount)
                json.put(
                    "items",
                    JSONArray().also { arr ->
                        s.items.forEach { item ->
                            arr.put(
                                JSONObject()
                                    .put("mediaId", item.mediaId)
                                    .put("name", item.name)
                                    .put("subtitle", item.subtitle),
                            )
                        }
                    },
                )
            }
            is RemoteMessage.Ack -> {
                json.put("t", T_ACK)
                json.put("ackSeq", message.ackSeq)
                json.put("ok", message.ok)
                json.put("message", message.message)
            }
        }
        return json.toString()
    }

    override fun decode(json: String): RemoteMessage? = try {
        decodeStrict(json)
    } catch (_: Exception) {
        null
    }

    private fun decodeStrict(text: String): RemoteMessage? {
        val json = JSONObject(text)
        if (json.optIntStrict("v") != REMOTE_PROTOCOL_VERSION) return null
        val type = json.requireString("t") ?: return null
        val seq = json.requireLong("seq") ?: return null
        val ts = json.requireLong("ts") ?: return null
        return when (type) {
            T_CMD -> {
                if (!json.keysWithin(cmdKeys)) return null
                val command = decodeCommand(json) ?: return null
                RemoteMessage.Command(seq = seq, sentAtMs = ts, command = command)
            }
            T_STATE -> {
                if (!json.keysWithin(stateKeys)) return null
                val state = decodeState(json) ?: return null
                RemoteMessage.State(seq = seq, sentAtMs = ts, state = state)
            }
            T_ACK -> {
                if (!json.keysWithin(ackKeys)) return null
                val ackSeq = json.requireLong("ackSeq") ?: return null
                val ok = json.requireBoolean("ok") ?: return null
                val message = json.optionalString("message") ?: return null
                RemoteMessage.Ack(seq = seq, sentAtMs = ts, ackSeq = ackSeq, ok = ok, message = message.value)
            }
            else -> null
        }
    }

    private fun decodeCommand(json: JSONObject): RemoteCommand? {
        val cmd = json.requireString("cmd") ?: return null
        val hasMediaId = json.has("mediaId")
        val hasPercent = json.has("percent")
        val hasGuestName = json.has("guestName")
        return when (cmd) {
            C_HELLO -> {
                if (hasMediaId || hasPercent) return null
                val name = json.requireString("guestName") ?: return null
                if (name.isBlank() || name.length > 40) return null
                RemoteCommand.Hello(name)
            }
            C_SELECT -> {
                if (hasPercent || hasGuestName) return null
                val mediaId = json.requireString("mediaId") ?: return null
                if (mediaId.isBlank()) return null
                RemoteCommand.Select(mediaId)
            }
            C_VOLUME -> {
                if (hasMediaId || hasGuestName) return null
                val percent = json.optIntStrict("percent") ?: return null
                if (percent !in 0..100) return null
                RemoteCommand.Volume(percent)
            }
            else -> {
                // 단순 명령은 인자를 가질 수 없다.
                if (hasMediaId || hasPercent || hasGuestName) return null
                when (cmd) {
                    C_BYE -> RemoteCommand.Bye
                    C_PLAY -> RemoteCommand.Play
                    C_PAUSE -> RemoteCommand.Pause
                    C_STOP -> RemoteCommand.Stop
                    C_NEXT -> RemoteCommand.Next
                    C_PREV -> RemoteCommand.Previous
                    C_REFRESH -> RemoteCommand.Refresh
                    C_BT_RECONNECT -> RemoteCommand.BtReconnect
                    C_ST_PAIR -> RemoteCommand.OpenSmartThingsPairing
                    else -> null
                }
            }
        }
    }

    private fun decodeState(json: JSONObject): HostState? {
        val mediaId = json.optionalString("mediaId") ?: return null
        val title = json.optionalString("title") ?: return null
        val artist = json.optionalString("artist") ?: return null
        val isPlaying = json.requireBoolean("isPlaying") ?: return null
        val playbackState = json.optIntStrict("playbackState") ?: return null
        val artworkRef = json.optionalString("artworkRef") ?: return null
        val bluetooth: BluetoothStatus? = if (json.has("bluetooth") && !json.isNull("bluetooth")) {
            val bt = json.get("bluetooth") as? JSONObject ?: return null
            if (!bt.keysWithin(bluetoothKeys)) return null
            val connected = bt.requireBoolean("connected") ?: return null
            val deviceName = bt.optionalString("deviceName") ?: return null
            val changedAtMs = bt.optionalLong("changedAtMs") ?: return null
            BluetoothStatus(connected = connected, deviceName = deviceName.value, changedAtMs = changedAtMs.value)
        } else {
            null
        }
        val batteryPercent = json.optionalInt("batteryPercent") ?: return null
        if (batteryPercent.value != null && batteryPercent.value !in 0..100) return null
        val updatedAtMs = json.requireLong("updatedAtMs") ?: return null
        val items = if (json.has("items")) {
            val arr = json.get("items") as? JSONArray ?: return null
            val list = ArrayList<RemoteItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.get(i) as? JSONObject ?: return null
                if (!obj.keysWithin(itemKeys)) return null
                val id = obj.requireString("mediaId") ?: return null
                val name = obj.requireString("name") ?: return null
                val subtitle = obj.requireString("subtitle") ?: return null
                list.add(RemoteItem(mediaId = id, name = name, subtitle = subtitle))
            }
            list
        } else {
            emptyList()
        }
        val volumePercent = json.optionalInt("volumePercent") ?: return null
        if (volumePercent.value != null && volumePercent.value !in 0..100) return null
        val guests = if (json.has("guests")) {
            val arr = json.get("guests") as? JSONArray ?: return null
            val list = ArrayList<RemoteGuest>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.get(i) as? JSONObject ?: return null
                if (!obj.keysWithin(guestKeys)) return null
                val name = obj.requireString("name") ?: return null
                val lastSeen = obj.requireLong("lastSeenMs") ?: return null
                list.add(RemoteGuest(name = name, lastSeenMs = lastSeen))
            }
            list
        } else {
            emptyList()
        }
        val revoked = if (json.has("revoked")) (json.requireBoolean("revoked") ?: return null) else false
        val stPairingOpenUntilMs = json.optionalLong("stPairingOpenUntilMs") ?: return null
        if (stPairingOpenUntilMs.value != null && stPairingOpenUntilMs.value < 0L) return null
        val stClientCount = json.optionalInt("stClientCount") ?: return null
        if (stClientCount.value != null && stClientCount.value !in 0..MAX_ST_CLIENTS) return null
        return HostState(
            mediaId = mediaId.value,
            title = title.value,
            artist = artist.value,
            isPlaying = isPlaying,
            playbackState = playbackState,
            artworkRef = artworkRef.value,
            bluetooth = bluetooth,
            batteryPercent = batteryPercent.value,
            updatedAtMs = updatedAtMs,
            items = items,
            volumePercent = volumePercent.value,
            guests = guests,
            revoked = revoked,
            stPairingOpenUntilMs = stPairingOpenUntilMs.value,
            stClientCount = stClientCount.value ?: 0,
        )
    }

    // ---- 엄격한 타입 접근자. org.json의 opt*는 문자열→숫자 강제 변환을 하므로 쓰지 않는다. ----

    // "없음"과 "잘못됨"을 구분하기 위한 래퍼: 잘못되면 null, 없으면 Optional(null).
    private class Optional<T>(val value: T?)

    private fun JSONObject.keysWithin(allowed: Set<String>): Boolean = keySet().all { it in allowed }

    private fun JSONObject.requireString(key: String): String? =
        if (has(key) && !isNull(key)) get(key) as? String else null

    private fun JSONObject.optionalString(key: String): Optional<String>? {
        if (!has(key) || isNull(key)) return Optional(null)
        val v = get(key) as? String ?: return null
        return Optional(v)
    }

    private fun JSONObject.requireBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) get(key) as? Boolean else null

    private fun Any.asIntegralLong(): Long? = when (this) {
        is Int -> toLong()
        is Long -> this
        is java.math.BigInteger -> if (bitLength() < 64) toLong() else null
        else -> null
    }

    private fun JSONObject.requireLong(key: String): Long? =
        if (has(key) && !isNull(key)) get(key).asIntegralLong() else null

    private fun JSONObject.optionalLong(key: String): Optional<Long>? {
        if (!has(key) || isNull(key)) return Optional(null)
        val v = get(key).asIntegralLong() ?: return null
        return Optional(v)
    }

    private fun JSONObject.optIntStrict(key: String): Int? {
        val v = requireLong(key) ?: return null
        return if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
    }

    private fun JSONObject.optionalInt(key: String): Optional<Int>? {
        if (!has(key) || isNull(key)) return Optional(null)
        val v = optIntStrict(key) ?: return null
        return Optional(v)
    }
}
