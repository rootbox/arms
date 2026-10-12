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

t.test("partial state leaves track data alone", function()
  t.is_nil(sm.normalize({ volume = 3 }, { partial = true })["audioTrackData.audioTrackData"])
end)

t.test("track without a title is omitted (hub rejects audioTrackData without title)", function()
  -- full state, no title at all
  t.is_nil(sm.normalize({ power = "off", playback = "stopped" })["audioTrackData.audioTrackData"])
  -- blank / whitespace titles count as missing, even with artist or art present
  for _, title in ipairs({ "", "   ", "\t" }) do
    t.is_nil(sm.normalize({ title = title, artist = "A", albumArtUrl = "http://x/1.png" })
      ["audioTrackData.audioTrackData"], string.format("title=%q", title))
  end
  t.is_nil(sm.normalize({ artist = "A" })["audioTrackData.audioTrackData"])
  t.is_nil(sm.map_track({ artist = "A" }))
  -- a real title is emitted and always carries `title`
  t.eq(sm.normalize({ title = "KBS", albumArtUrl = "http://x/1.png" })["audioTrackData.audioTrackData"],
    { title = "KBS", albumArtUrl = "http://x/1.png" })
  -- and no event is produced for the track, even on a forced refresh
  local snap = {}
  local evs = sm.events_for_state(snap, { power = "on", playback = "playing" }, { force = true })
  t.eq(#evs, 2)
  t.is_nil(by_key(evs)["audioTrackData.audioTrackData"])
  t.is_nil(snap["audioTrackData.audioTrackData"])
  -- the previously emitted track stays in the snapshot (app keeps showing it)
  snap = {}
  sm.events_for_state(snap, FULL)
  t.eq(#sm.events_for_state(snap, { power = "on", playback = "playing" }), 0)
  t.eq(snap["audioTrackData.audioTrackData"].title, FULL.title)
end)

t.test("force as a key set re-emits only those keys even if unchanged", function()
  local snap = {}
  sm.events_for_state(snap, FULL)
  local evs = sm.events_for_state(snap, FULL, { force = { ["switch.switch"] = true } })
  t.eq(#evs, 1)
  t.eq(evs[1].key, "switch.switch")
  t.eq(evs[1].value, "on")
  -- a changed attribute outside the set is still emitted
  local changed = {}
  for k, v in pairs(FULL) do changed[k] = v end
  changed.volume = 42
  evs = sm.events_for_state(snap, changed, { force = { ["switch.switch"] = true } })
  t.eq(#evs, 2)
  t.eq(evs[1].key, "switch.switch")
  t.eq(evs[2].key, "audioVolume.volume")
  -- a forced key absent from the state is not invented
  evs = sm.events_for_state(snap, { volume = 42 }, { force = { ["switch.switch"] = true } })
  t.eq(#evs, 0)
end)

t.test("snapshot_events re-emits everything last emitted, in ORDER", function()
  local snap = {}
  sm.events_for_state(snap, FULL)
  local evs = sm.snapshot_events(snap)
  t.eq(#evs, 6)
  t.eq(evs[1].key, "switch.switch")
  t.eq(evs[6].key, "audioTrackData.audioTrackData")
  t.eq(evs[6].value.title, FULL.title)
  t.eq(#sm.snapshot_events({}), 0)
  -- the snapshot is unchanged afterwards (dedupe keeps working)
  t.eq(#sm.events_for_state(snap, FULL), 0)
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
