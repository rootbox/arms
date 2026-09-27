package com.arms.androidauto.core.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRemoteCodecTest {
    private val codec = JsonRemoteCodec

    private val allCommands = listOf(
        RemoteCommand.Play,
        RemoteCommand.Pause,
        RemoteCommand.Stop,
        RemoteCommand.Next,
        RemoteCommand.Previous,
        RemoteCommand.Refresh,
        RemoteCommand.BtReconnect,
        RemoteCommand.Select("station:4"),
        RemoteCommand.Volume(0),
        RemoteCommand.Volume(100),
        RemoteCommand.Hello("Galaxy S25"),
        RemoteCommand.Hello("a".repeat(40)),
        RemoteCommand.Bye,
    )

    private val fullState = HostState(
        mediaId = "nas:album:12",
        title = "Kind of Blue",
        artist = "Miles Davis",
        isPlaying = true,
        playbackState = 3,
        artworkRef = "station:4",
        bluetooth = BluetoothStatus(connected = false, deviceName = "JBL Flip", changedAtMs = 1_700_000_000_000L),
        batteryPercent = 87,
        updatedAtMs = 1_700_000_001_000L,
        items = listOf(
            RemoteItem("station:1", "CBS 음악FM", "93.9"),
            RemoteItem("nas:album:12", "Kind of Blue", "Miles Davis"),
        ),
    )

    @Test
    fun `every command round trips`() {
        allCommands.forEachIndexed { i, cmd ->
            val msg = RemoteMessage.Command(seq = 100L + i, sentAtMs = 5_000L + i, command = cmd)
            val decoded = codec.decode(codec.encode(msg))
            assertEquals("command $cmd", msg, decoded)
        }
    }

    @Test
    fun `full state round trips`() {
        val msg = RemoteMessage.State(seq = 42, sentAtMs = 9_999L, state = fullState)
        assertEquals(msg, codec.decode(codec.encode(msg)))
    }

    @Test
    fun `state with all optionals null round trips`() {
        val state = HostState(
            mediaId = null, title = null, artist = null, isPlaying = false, playbackState = 1,
            artworkRef = null, bluetooth = null, batteryPercent = null, updatedAtMs = 1L,
        )
        val msg = RemoteMessage.State(seq = 1, sentAtMs = 1, state = state)
        val text = codec.encode(msg)
        val json = JSONObject(text)
        assertTrue("null fields are omitted", !json.has("mediaId") && !json.has("bluetooth"))
        assertEquals(msg, codec.decode(text))
    }

    @Test
    fun `ack round trips with and without message`() {
        val withMsg = RemoteMessage.Ack(seq = 7, sentAtMs = 8, ackSeq = 6, ok = false, message = "unknown mediaId")
        val without = RemoteMessage.Ack(seq = 9, sentAtMs = 10, ackSeq = 8, ok = true, message = null)
        assertEquals(withMsg, codec.decode(codec.encode(withMsg)))
        assertEquals(without, codec.decode(codec.encode(without)))
    }

    @Test
    fun `envelope has version and type`() {
        val json = JSONObject(codec.encode(RemoteMessage.Command(1, 2, RemoteCommand.Play)))
        assertEquals(REMOTE_PROTOCOL_VERSION, json.getInt("v"))
        assertEquals("cmd", json.getString("t"))
        assertEquals("play", json.getString("cmd"))
        assertEquals(setOf("v", "t", "seq", "ts", "cmd"), json.keySet())
    }

    // ---- 화이트리스트 거부 ----

    private fun cmdJson(cmd: String, extra: (JSONObject) -> Unit = {}): String =
        JSONObject().put("v", 1).put("t", "cmd").put("seq", 1L).put("ts", 2L).put("cmd", cmd).also(extra).toString()

    @Test
    fun `unknown top level key is rejected`() {
        assertNotNull(codec.decode(cmdJson("play")))
        assertNull(codec.decode(cmdJson("play") { it.put("location", "37.5,127.0") }))
    }

    @Test
    fun `unknown nested key in state is rejected`() {
        val text = codec.encode(RemoteMessage.State(1, 2, fullState))
        val json = JSONObject(text)
        json.getJSONObject("bluetooth").put("macAddress", "00:11:22:33:44:55")
        assertNull(codec.decode(json.toString()))

        val json2 = JSONObject(text)
        json2.getJSONArray("items").getJSONObject(0).put("streamUrl", "http://nas/secret")
        assertNull(codec.decode(json2.toString()))
    }

    @Test
    fun `unknown cmd is rejected`() {
        assertNull(codec.decode(cmdJson("shutdown")))
        assertNull(codec.decode(cmdJson("")))
    }

    @Test
    fun `unknown type is rejected`() {
        val text = JSONObject().put("v", 1).put("t", "file").put("seq", 1L).put("ts", 2L).toString()
        assertNull(codec.decode(text))
    }

    @Test
    fun `bad version is rejected`() {
        val text = JSONObject().put("v", 2).put("t", "cmd").put("seq", 1L).put("ts", 2L).put("cmd", "play").toString()
        assertNull(codec.decode(text))
        val noVersion = JSONObject().put("t", "cmd").put("seq", 1L).put("ts", 2L).put("cmd", "play").toString()
        assertNull(codec.decode(noVersion))
    }

    @Test
    fun `volume out of range is rejected`() {
        assertNull(codec.decode(cmdJson("volume") { it.put("percent", 101) }))
        assertNull(codec.decode(cmdJson("volume") { it.put("percent", -1) }))
        assertNull(codec.decode(cmdJson("volume")))
        assertNull(codec.decode(cmdJson("volume") { it.put("percent", "50") }))
        assertEquals(RemoteCommand.Volume(50), (codec.decode(cmdJson("volume") { it.put("percent", 50) }) as RemoteMessage.Command).command)
    }

    @Test
    fun `select with blank mediaId is rejected`() {
        assertNull(codec.decode(cmdJson("select") { it.put("mediaId", "") }))
        assertNull(codec.decode(cmdJson("select") { it.put("mediaId", "   ") }))
        assertNull(codec.decode(cmdJson("select")))
    }

    @Test
    fun `simple commands with stray arguments are rejected`() {
        assertNull(codec.decode(cmdJson("play") { it.put("mediaId", "station:1") }))
        assertNull(codec.decode(cmdJson("next") { it.put("percent", 3) }))
    }

    @Test
    fun `missing or wrongly typed envelope fields are rejected`() {
        assertNull(codec.decode(JSONObject().put("v", 1).put("t", "cmd").put("ts", 2L).put("cmd", "play").toString()))
        assertNull(codec.decode(JSONObject().put("v", 1).put("t", "cmd").put("seq", "1").put("ts", 2L).put("cmd", "play").toString()))
        assertNull(codec.decode(JSONObject().put("v", "1").put("t", "cmd").put("seq", 1L).put("ts", 2L).put("cmd", "play").toString()))
    }

    @Test
    fun `garbage input returns null instead of throwing`() {
        assertNull(codec.decode(""))
        assertNull(codec.decode("not json"))
        assertNull(codec.decode("[1,2,3]"))
        assertNull(codec.decode("{}"))
    }

    // ---- Hello/Bye · volumePercent/guests/revoked ----

    private val presenceState = fullState.copy(
        volumePercent = 35,
        guests = listOf(
            RemoteGuest("Galaxy S25", 1_700_000_002_000L),
            RemoteGuest("cli-guest", 1_700_000_003_000L),
        ),
        revoked = true,
    )

    @Test
    fun `hello and bye round trip`() {
        val hello = RemoteMessage.Command(seq = 11, sentAtMs = 12, command = RemoteCommand.Hello("Galaxy S25"))
        val bye = RemoteMessage.Command(seq = 13, sentAtMs = 14, command = RemoteCommand.Bye)
        assertEquals(hello, codec.decode(codec.encode(hello)))
        assertEquals(bye, codec.decode(codec.encode(bye)))
        val helloJson = JSONObject(codec.encode(hello))
        assertEquals("hello", helloJson.getString("cmd"))
        assertEquals("Galaxy S25", helloJson.getString("guestName"))
        assertEquals(setOf("v", "t", "seq", "ts", "cmd", "guestName"), helloJson.keySet())
        assertEquals(setOf("v", "t", "seq", "ts", "cmd"), JSONObject(codec.encode(bye)).keySet())
    }

    @Test
    fun `state with volume guests and revoked round trips`() {
        val msg = RemoteMessage.State(seq = 43, sentAtMs = 9_998L, state = presenceState)
        val text = codec.encode(msg)
        val json = JSONObject(text)
        assertEquals(35, json.getInt("volumePercent"))
        assertTrue(json.getBoolean("revoked"))
        val guests = json.getJSONArray("guests")
        assertEquals(2, guests.length())
        assertEquals(setOf("name", "lastSeenMs"), guests.getJSONObject(0).keySet())
        assertEquals("Galaxy S25", guests.getJSONObject(0).getString("name"))
        assertEquals(1_700_000_002_000L, guests.getJSONObject(0).getLong("lastSeenMs"))
        assertEquals(msg, codec.decode(text))
    }

    @Test
    fun `hello without guestName is rejected`() {
        assertNull(codec.decode(cmdJson("hello")))
        assertNull(codec.decode(cmdJson("hello") { it.put("guestName", "") }))
        assertNull(codec.decode(cmdJson("hello") { it.put("guestName", "   ") }))
        assertNull(codec.decode(cmdJson("hello") { it.put("guestName", 42) }))
    }

    @Test
    fun `hello with mediaId or percent is rejected`() {
        assertNull(codec.decode(cmdJson("hello") { it.put("guestName", "Galaxy").put("mediaId", "station:1") }))
        assertNull(codec.decode(cmdJson("hello") { it.put("guestName", "Galaxy").put("percent", 10) }))
    }

    @Test
    fun `guestName longer than 40 chars is rejected`() {
        assertNull(codec.decode(cmdJson("hello") { it.put("guestName", "a".repeat(41)) }))
        val ok = codec.decode(cmdJson("hello") { it.put("guestName", "a".repeat(40)) }) as RemoteMessage.Command
        assertEquals(RemoteCommand.Hello("a".repeat(40)), ok.command)
    }

    @Test
    fun `bye and other simple commands reject guestName`() {
        assertNull(codec.decode(cmdJson("bye") { it.put("guestName", "Galaxy") }))
        assertNull(codec.decode(cmdJson("play") { it.put("guestName", "Galaxy") }))
        assertNull(codec.decode(cmdJson("select") { it.put("mediaId", "station:1").put("guestName", "Galaxy") }))
        assertNull(codec.decode(cmdJson("volume") { it.put("percent", 5).put("guestName", "Galaxy") }))
        assertEquals(RemoteCommand.Bye, (codec.decode(cmdJson("bye")) as RemoteMessage.Command).command)
    }

    @Test
    fun `guest object with unknown key is rejected`() {
        val json = JSONObject(codec.encode(RemoteMessage.State(1, 2, presenceState)))
        json.getJSONArray("guests").getJSONObject(1).put("ip", "10.0.0.7")
        assertNull(codec.decode(json.toString()))

        val missingName = JSONObject(codec.encode(RemoteMessage.State(1, 2, presenceState)))
        missingName.getJSONArray("guests").getJSONObject(0).remove("name")
        assertNull(codec.decode(missingName.toString()))

        val notObject = JSONObject(codec.encode(RemoteMessage.State(1, 2, presenceState)))
        notObject.put("guests", org.json.JSONArray().put("Galaxy S25"))
        assertNull(codec.decode(notObject.toString()))
    }

    @Test
    fun `state volumePercent out of range is rejected`() {
        val json = JSONObject(codec.encode(RemoteMessage.State(1, 2, presenceState)))
        json.put("volumePercent", 101)
        assertNull(codec.decode(json.toString()))
        json.put("volumePercent", -1)
        assertNull(codec.decode(json.toString()))
        json.put("volumePercent", "50")
        assertNull(codec.decode(json.toString()))
        json.put("volumePercent", 100)
        assertNotNull(codec.decode(json.toString()))
    }

    @Test
    fun `revoked non-boolean is rejected`() {
        val json = JSONObject(codec.encode(RemoteMessage.State(1, 2, presenceState)))
        json.put("revoked", "true")
        assertNull(codec.decode(json.toString()))
        json.put("revoked", 1)
        assertNull(codec.decode(json.toString()))
        json.put("revoked", false)
        val decoded = codec.decode(json.toString()) as RemoteMessage.State
        assertEquals(false, decoded.state.revoked)
    }

    @Test
    fun `state json without new keys decodes with defaults`() {
        // 구(舊) 호스트가 보낸 state: volumePercent/guests/revoked 키 없음.
        val legacy = JSONObject()
            .put("v", 1).put("t", "state").put("seq", 5L).put("ts", 6L)
            .put("mediaId", "station:1").put("title", "Fake FM").put("artist", "news")
            .put("isPlaying", true).put("playbackState", 3).put("batteryPercent", 50)
            .put("updatedAtMs", 7L)
            .put("items", org.json.JSONArray().put(JSONObject().put("mediaId", "station:1").put("name", "Fake FM").put("subtitle", "news")))
            .toString()
        val decoded = codec.decode(legacy) as RemoteMessage.State
        assertNull(decoded.state.volumePercent)
        assertEquals(emptyList<RemoteGuest>(), decoded.state.guests)
        assertEquals(false, decoded.state.revoked)
    }

    @Test
    fun `default state omits guests and revoked keys`() {
        // 게스트 없음·revoked=false·volume null 이면 새 키를 아예 쓰지 않는다(구 게스트 호환).
        val json = JSONObject(codec.encode(RemoteMessage.State(1, 2, fullState)))
        assertTrue(!json.has("guests") && !json.has("revoked") && !json.has("volumePercent"))
    }
}
