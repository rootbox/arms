-- SmartThings capability command -> Simple Radio host command (pure Lua).
--
-- Contract: smartthings/LAN_API.md ("명령" table). The result is the JSON body for
-- POST /api/v1/command: { command = "<name>", value = <optional> }.

local state_map = require "simpleradio.state_map"

local M = {}

-- [capability][command] = function(args) -> body | nil, err
local MAP = {
  switch = {
    on = function() return { command = "on" } end,
    off = function() return { command = "off" } end,
  },
  mediaPlayback = {
    play = function() return { command = "play" } end,
    pause = function() return { command = "pause" } end,
    stop = function() return { command = "stop" } end,
  },
  mediaTrackControl = {
    nextTrack = function() return { command = "next" } end,
    previousTrack = function() return { command = "previous" } end,
  },
  audioVolume = {
    setVolume = function(args)
      local v = state_map.clamp_volume(args and args.volume)
      if v == nil then return nil, "setVolume needs a numeric volume" end
      return { command = "setVolume", value = v }
    end,
    volumeUp = function() return { command = "volumeUp" } end,
    volumeDown = function() return { command = "volumeDown" } end,
  },
  audioMute = {
    mute = function() return { command = "mute" } end,
    unmute = function() return { command = "unmute" } end,
    setMute = function(args)
      local s = args and args.state
      if s == "muted" then return { command = "mute" } end
      if s == "unmuted" then return { command = "unmute" } end
      return nil, "setMute needs state muted|unmuted, got " .. tostring(s)
    end,
  },
  mediaPresets = {
    playPreset = function(args)
      local id = args and args.presetId
      if type(id) == "number" then id = tostring(math.tointeger(id) or id) end
      if type(id) ~= "string" or id == "" then return nil, "playPreset needs presetId" end
      return { command = "playPreset", value = id }
    end,
  },
}

--- @return table|nil body, string|nil err
function M.to_request(capability, command, args)
  local cap = MAP[capability]
  local fn = cap and cap[command]
  if not fn then
    return nil, string.format("unsupported command %s.%s", tostring(capability), tostring(command))
  end
  return fn(args or {})
end

--- Supported (capability, command) pairs, for registering handlers.
function M.supported()
  local list = {}
  for cap, cmds in pairs(MAP) do
    for cmd in pairs(cmds) do list[#list + 1] = { capability = cap, command = cmd } end
  end
  table.sort(list, function(a, b)
    if a.capability == b.capability then return a.command < b.command end
    return a.capability < b.capability
  end)
  return list
end

--- Partial host state to assume after a successful command (optimistic UI update).
--- Only setVolume / mute / unmute are reliable enough to show before verification: the host
--- applies them synchronously. Power/playback commands return 200 even when the player did
--- nothing (released MediaController, 2026-10-12), so they wait for the verification read
--- (handlers.send_command) instead of being assumed.
--- @param body table output of M.to_request
--- @return table|nil partial state
function M.optimistic_state(body)
  if type(body) ~= "table" then return nil end
  local c = body.command
  if c == "setVolume" then return { volume = body.value }
  elseif c == "mute" then return { muted = true }
  elseif c == "unmute" then return { muted = false }
  end
  return nil
end

-- command -> state_map keys the SmartThings app is waiting on (its tile shows a spinner until
-- an event for that attribute arrives), so the verification read always re-emits them.
local VERIFY_KEYS = {
  on = { "switch.switch", "mediaPlayback.playbackStatus" },
  off = { "switch.switch", "mediaPlayback.playbackStatus" },
  play = { "switch.switch", "mediaPlayback.playbackStatus" },
  pause = { "mediaPlayback.playbackStatus" },
  stop = { "mediaPlayback.playbackStatus" },
  next = { "audioTrackData.audioTrackData" },
  previous = { "audioTrackData.audioTrackData" },
  playPreset = { "switch.switch", "mediaPlayback.playbackStatus", "audioTrackData.audioTrackData" },
  setVolume = { "audioVolume.volume" },
  volumeUp = { "audioVolume.volume" },
  volumeDown = { "audioVolume.volume" },
  mute = { "audioMute.mute" },
  unmute = { "audioMute.mute" },
}

--- Attribute keys (state_map.ORDER keys) that must be emitted even if unchanged once the
--- host state is re-read after `body`, so the app leaves its pending state with the real value.
--- @param body table output of M.to_request
--- @return table set { [key] = true }
function M.verify_keys(body)
  local set = {}
  local list = type(body) == "table" and VERIFY_KEYS[body.command] or nil
  for _, k in ipairs(list or {}) do set[k] = true end
  return set
end

return M
