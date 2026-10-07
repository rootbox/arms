local t = require "t"
local c = require "simpleradio.commands"

t.test("simple commands", function()
  local cases = {
    { "switch", "on", "on" }, { "switch", "off", "off" },
    { "mediaPlayback", "play", "play" }, { "mediaPlayback", "pause", "pause" },
    { "mediaPlayback", "stop", "stop" },
    { "mediaTrackControl", "nextTrack", "next" }, { "mediaTrackControl", "previousTrack", "previous" },
    { "audioVolume", "volumeUp", "volumeUp" }, { "audioVolume", "volumeDown", "volumeDown" },
    { "audioMute", "mute", "mute" }, { "audioMute", "unmute", "unmute" },
  }
  for _, case in ipairs(cases) do
    local body, err = c.to_request(case[1], case[2], {})
    t.is_nil(err, case[1] .. "." .. case[2])
    t.eq(body, { command = case[3] }, case[1] .. "." .. case[2])
  end
end)

t.test("setVolume clamps and requires a number", function()
  t.eq(c.to_request("audioVolume", "setVolume", { volume = 55 }), { command = "setVolume", value = 55 })
  t.eq(c.to_request("audioVolume", "setVolume", { volume = 120 }), { command = "setVolume", value = 100 })
  local body, err = c.to_request("audioVolume", "setVolume", {})
  t.is_nil(body)
  t.match(err, "numeric")
end)

t.test("setMute maps state", function()
  t.eq(c.to_request("audioMute", "setMute", { state = "muted" }), { command = "mute" })
  t.eq(c.to_request("audioMute", "setMute", { state = "unmuted" }), { command = "unmute" })
  t.is_nil((c.to_request("audioMute", "setMute", { state = "loud" })))
end)

t.test("playPreset passes the preset id as value", function()
  t.eq(c.to_request("mediaPresets", "playPreset", { presetId = "3" }), { command = "playPreset", value = "3" })
  t.eq(c.to_request("mediaPresets", "playPreset", { presetId = 3 }), { command = "playPreset", value = "3" })
  t.is_nil((c.to_request("mediaPresets", "playPreset", { presetId = "" })))
end)

t.test("unknown command", function()
  local body, err = c.to_request("mediaPlayback", "fastForward", {})
  t.is_nil(body)
  t.match(err, "unsupported")
end)

t.test("supported() lists every mapped command (14)", function()
  t.eq(#c.supported(), 14)
end)

t.test("optimistic state only for deterministic commands", function()
  t.eq(c.optimistic_state({ command = "setVolume", value = 30 }), { volume = 30 })
  t.eq(c.optimistic_state({ command = "mute" }), { muted = true })
  t.eq(c.optimistic_state({ command = "pause" }), { playback = "paused" })
  t.is_nil(c.optimistic_state({ command = "volumeUp" }))
  t.is_nil(c.optimistic_state({ command = "next" }))
  t.is_nil(c.optimistic_state({ command = "playPreset", value = "1" }))
end)

t.run("test_commands")
