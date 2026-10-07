package com.arms.androidauto.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserTest {

    private fun ok(body: String): LocalCommand {
        val r = LocalCommandParser.parse(body)
        assertTrue("expected Ok for $body but was $r", r is LocalCommandParser.Result.Ok)
        return (r as LocalCommandParser.Result.Ok).command
    }

    private fun invalid(body: String) {
        val r = LocalCommandParser.parse(body)
        assertTrue("expected Invalid for $body but was $r", r is LocalCommandParser.Result.Invalid)
    }

    @Test
    fun simpleCommands() {
        assertEquals(LocalCommand.On, ok("""{"command":"on"}"""))
        assertEquals(LocalCommand.Off, ok("""{"command":"off"}"""))
        assertEquals(LocalCommand.Play, ok("""{"command":"play"}"""))
        assertEquals(LocalCommand.Pause, ok("""{"command":"pause"}"""))
        assertEquals(LocalCommand.Stop, ok("""{"command":"stop"}"""))
        assertEquals(LocalCommand.Next, ok("""{"command":"next"}"""))
        assertEquals(LocalCommand.Previous, ok("""{"command":"previous"}"""))
        assertEquals(LocalCommand.VolumeUp, ok("""{"command":"volumeUp"}"""))
        assertEquals(LocalCommand.VolumeDown, ok("""{"command":"volumeDown"}"""))
        assertEquals(LocalCommand.Mute, ok("""{"command":"mute"}"""))
        assertEquals(LocalCommand.Unmute, ok("""{"command":"unmute","value":null}"""))
    }

    @Test
    fun setVolumeRange() {
        assertEquals(LocalCommand.SetVolume(0), ok("""{"command":"setVolume","value":0}"""))
        assertEquals(LocalCommand.SetVolume(100), ok("""{"command":"setVolume","value":100}"""))
        assertEquals(LocalCommand.SetVolume(40), ok("""{"command":"setVolume","value":"40"}"""))
        assertEquals(LocalCommand.SetVolume(41), ok("""{"command":"setVolume","value":40.6}"""))
        invalid("""{"command":"setVolume","value":101}""")
        invalid("""{"command":"setVolume","value":-1}""")
        invalid("""{"command":"setVolume"}""")
        invalid("""{"command":"setVolume","value":"loud"}""")
        invalid("""{"command":"setVolume","value":true}""")
    }

    @Test
    fun playPreset() {
        assertEquals(LocalCommand.PlayPreset("3"), ok("""{"command":"playPreset","value":"3"}"""))
        assertEquals(LocalCommand.PlayPreset("3"), ok("""{"command":"playPreset","value":3}"""))
        invalid("""{"command":"playPreset"}""")
        invalid("""{"command":"playPreset","value":""}""")
        invalid("""{"command":"playPreset","value":1.5}""")
    }

    @Test
    fun unknownOrMalformedIsInvalid() {
        invalid("""{"command":"selfDestruct"}""")
        invalid("""{"command":"ON"}""")
        invalid("""{"value":1}""")
        invalid("""{"command":5}""")
        invalid("""not json""")
        invalid("""[1,2]""")
        invalid("")
    }
}
