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
--- Only deterministic outcomes are returned; relative or ambiguous commands
--- (volumeUp/Down, next/previous, playPreset) wait for the real state from the host.
--- @param body table output of M.to_request
--- @return table|nil partial state
function M.optimistic_state(body)
  if type(body) ~= "table" then return nil end
  local c = body.command
  if c == "on" then return { power = "on" }
  elseif c == "off" then return { power = "off", playback = "stopped" }
  elseif c == "play" then return { power = "on", playback = "playing" }
  elseif c == "pause" then return { playback = "paused" }
  elseif c == "stop" then return { playback = "stopped" }
  elseif c == "setVolume" then return { volume = body.value }
  elseif c == "mute" then return { muted = true }
  elseif c == "unmute" then return { muted = false }
  end
  return nil
end

return M
