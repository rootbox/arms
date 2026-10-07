local t = require "t"
local sm = require "simpleradio.state_map"

local FULL = {
  power = "on",
  playback = "playing",
  mediaId = "1",
  title = "KBS Cool FM - 프로그램명",
  artist = "프로그램명",
  albumArtUrl = "http://192.168.0.10:8765/art/station/1.png",
  volume = 40,
  muted = false,
  presets = {
    { id = "1", name = "KBS Cool FM", imageUrl = "http://192.168.0.10:8765/art/station/1.png" },
    { id = "2", name = "MBC FM4U", imageUrl = "" },
  },
  updatedAtMs = 1791358000000,
}

local function by_key(events)
  local m = {}
  for _, e in ipairs(events) do m[e.key] = e end
  return m
end

t.test("full state maps to every capability attribute", function()
  local snap = {}
  local evs = sm.events_for_state(snap, FULL)
  local m = by_key(evs)
  t.eq(m["switch.switch"].value, "on")
  t.eq(m["mediaPlayback.playbackStatus"].value, "playing")
  t.eq(m["audioVolume.volume"].value, 40)
  t.eq(math.type(m["audioVolume.volume"].value), "integer")
  t.eq(m["audioMute.mute"].value, "unmuted")
  t.eq(m["mediaPresets.presets"].value, {
    { id = "1", name = "KBS Cool FM", imageUrl = "http://192.168.0.10:8765/art/station/1.png" },
    { id = "2", name = "MBC FM4U" },
  })
  t.eq(m["audioTrackData.audioTrackData"].value, {
    title = "KBS Cool FM - 프로그램명", artist = "프로그램명",
    albumArtUrl = "http://192.168.0.10:8765/art/station/1.png",
  })
  t.is_nil(m["audioTrackData.totalTime"], "live radio: no totalTime")
  t.is_nil(m["audioTrackData.elapsedTime"], "live radio: no elapsedTime")
  t.eq(#evs, 6)
  t.eq(evs[1].capability, "switch")
  t.eq(evs[1].attribute, "switch")
end)

t.test("identical state emits nothing; a change emits only that attribute", function()
  local snap = {}
  sm.events_for_state(snap, FULL)
  t.eq(#sm.events_for_state(snap, FULL), 0)
  local changed = {}
  for k, v in pairs(FULL) do changed[k] = v end
  changed.volume = 41
  changed.updatedAtMs = FULL.updatedAtMs + 1
  local evs = sm.events_for_state(snap, changed)
  t.eq(#evs, 1)
  t.eq(evs[1].key, "audioVolume.volume")
  t.eq(evs[1].value, 41)
end)

t.test("force re-emits unchanged values (refresh)", function()
  local snap = {}
  sm.events_for_state(snap, FULL)
  t.eq(#sm.events_for_state(snap, FULL, { force = true }), 6)
end)

t.test("playback mapping incl. buffering -> playing", function()
  for from, to in pairs({ playing = "playing", paused = "paused", stopped = "stopped", buffering = "playing" }) do
    t.eq(sm.normalize({ playback = from })["mediaPlayback.playbackStatus"], to, from)
  end
  t.is_nil(sm.normalize({ playback = "weird" })["mediaPlayback.playbackStatus"])
end)

t.test("buffering -> playing is not a change when already playing", function()
  local snap = {}
  sm.events_for_state(snap, { playback = "playing" }, { partial = true })
  t.eq(#sm.events_for_state(snap, { playback = "buffering" }, { partial = true }), 0)
end)

t.test("volume clamps and rounds; strings accepted; junk ignored", function()
  t.eq(sm.normalize({ volume = 150 })["audioVolume.volume"], 100)
  t.eq(sm.normalize({ volume = -3 })["audioVolume.volume"], 0)
  t.eq(sm.normalize({ volume = 39.6 })["audioVolume.volume"], 40)
  t.eq(math.type(sm.normalize({ volume = 40.0 })["audioVolume.volume"]), "integer")
  t.eq(sm.normalize({ volume = "55" })["audioVolume.volume"], 55)
  t.is_nil(sm.normalize({ volume = "loud" })["audioVolume.volume"])
end)

t.test("muted true -> muted", function()
  t.eq(sm.normalize({ muted = true })["audioMute.mute"], "muted")
end)

t.test("partial state leaves track data alone; full state without track clears it", function()
  t.is_nil(sm.normalize({ volume = 3 }, { partial = true })["audioTrackData.audioTrackData"])
  t.eq(sm.normalize({ power = "off", playback = "stopped" })["audioTrackData.audioTrackData"], {})
end)

t.test("presets: numeric ids stringified, missing name -> id, entries without id skipped", function()
  local p = sm.normalize({ presets = { { id = 3 }, { name = "no id" }, "junk", { id = "x", name = "X" } } })
  t.eq(p["mediaPresets.presets"], { { id = "3", name = "3" }, { id = "x", name = "X" } })
end)

t.test("empty preset list is emitted (clears the list)", function()
  local snap = { ["mediaPresets.presets"] = { { id = "1", name = "a" } } }
  local evs = sm.events_for_state(snap, { presets = {} }, { partial = true })
  t.eq(#evs, 1)
  t.eq(evs[1].value, {})
end)

t.test("durations only when a positive durationMs is present", function()
  local v = sm.normalize({ durationMs = 185000, positionMs = 61500 })
  t.eq(v["audioTrackData.totalTime"], 185)
  t.eq(v["audioTrackData.elapsedTime"], 61)
  t.is_nil(sm.normalize({ durationMs = 0, positionMs = 5 })["audioTrackData.totalTime"])
end)

t.test("non-table input yields nothing", function()
  t.eq(sm.normalize(nil), {})
  t.eq(sm.normalize("x"), {})
end)

t.test("snapshot values are copies (mutating emitted value does not corrupt dedupe)", function()
  local snap = {}
  local evs = sm.events_for_state(snap, FULL)
  local m = by_key(evs)
  m["mediaPresets.presets"].value[1].name = "mutated"
  t.eq(#sm.events_for_state(snap, FULL), 0)
end)

t.run("test_state_map")
