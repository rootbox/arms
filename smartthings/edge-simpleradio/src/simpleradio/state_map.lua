-- Host state JSON -> SmartThings attribute values (pure Lua, no ST dependencies).
--
-- The mapping is expressed as neutral "descriptors":
--   { capability = "audioVolume", attribute = "volume", value = 40 }
-- The SmartThings layer (simpleradio.emitter) turns them into capability events.
-- Only changed values are produced, so the driver emits only on change.
--
-- Contract: smartthings/LAN_API.md ("상태 JSON" and "SmartThings 매핑").

local M = {}

-- Stable emission order (also the list of every attribute this driver owns).
M.ORDER = {
  { key = "switch.switch",                 capability = "switch",         attribute = "switch" },
  { key = "mediaPlayback.playbackStatus",  capability = "mediaPlayback",  attribute = "playbackStatus" },
  { key = "audioVolume.volume",            capability = "audioVolume",    attribute = "volume" },
  { key = "audioMute.mute",                capability = "audioMute",      attribute = "mute" },
  { key = "mediaPresets.presets",          capability = "mediaPresets",   attribute = "presets" },
  { key = "audioTrackData.audioTrackData", capability = "audioTrackData", attribute = "audioTrackData" },
  { key = "audioTrackData.totalTime",      capability = "audioTrackData", attribute = "totalTime" },
  { key = "audioTrackData.elapsedTime",    capability = "audioTrackData", attribute = "elapsedTime" },
}

-- playback -> mediaPlayback.playbackStatus (buffering is shown as playing, per contract)
M.PLAYBACK_MAP = {
  playing = "playing",
  paused = "paused",
  stopped = "stopped",
  buffering = "playing",
}

local function nonempty_string(v)
  if type(v) == "string" and v ~= "" then return v end
  if type(v) == "number" then return tostring(v) end
  return nil
end

local function to_int(v)
  if type(v) == "string" then v = tonumber(v) end
  if type(v) ~= "number" or v ~= v then return nil end -- reject NaN
  local i = math.floor(v + 0.5)
  return math.tointeger(i) or i
end

function M.clamp_volume(v)
  local i = to_int(v)
  if i == nil then return nil end
  if i < 0 then i = 0 elseif i > 100 then i = 100 end
  return i
end

local function to_bool(v)
  if v == true or v == "true" then return true end
  if v == false or v == "false" then return false end
  return nil
end

local function map_presets(list)
  if type(list) ~= "table" then return nil end
  local out = {}
  for _, p in ipairs(list) do
    if type(p) == "table" then
      local id = nonempty_string(p.id)
      if id then
        local item = { id = id, name = nonempty_string(p.name) or id }
        local img = nonempty_string(p.imageUrl)
        if img then item.imageUrl = img end
        out[#out + 1] = item
      end
    end
  end
  return out
end

local function map_track(state)
  local track = {}
  local title = nonempty_string(state.title)
  local artist = nonempty_string(state.artist)
  local art = nonempty_string(state.albumArtUrl)
  if title then track.title = title end
  if artist then track.artist = artist end
  if art then track.albumArtUrl = art end
  return track
end

--- Convert a host state table into { [key] = value } for the attributes it describes.
---
--- @param state table decoded state JSON
--- @param opts table|nil { partial = true } for optimistic patches: absent fields are left
---                     untouched instead of being cleared (track data).
--- @return table values keyed like M.ORDER[i].key
function M.normalize(state, opts)
  local values = {}
  if type(state) ~= "table" then return values end
  local partial = opts and opts.partial

  if state.power == "on" or state.power == "off" then
    values["switch.switch"] = state.power
  elseif state.power == true or state.power == false then
    values["switch.switch"] = state.power and "on" or "off"
  end

  local status = M.PLAYBACK_MAP[state.playback]
  if status then values["mediaPlayback.playbackStatus"] = status end

  local vol = M.clamp_volume(state.volume)
  if vol ~= nil then values["audioVolume.volume"] = vol end

  local muted = to_bool(state.muted)
  if muted ~= nil then values["audioMute.mute"] = muted and "muted" or "unmuted" end

  local presets = map_presets(state.presets)
  if presets then values["mediaPresets.presets"] = presets end

  if not partial or state.title ~= nil or state.artist ~= nil or state.albumArtUrl ~= nil then
    values["audioTrackData.audioTrackData"] = map_track(state)
  end

  -- Not part of contract v1 (live radio has no duration). Only reported if a future host
  -- version sends a positive durationMs, so on-demand content could show progress.
  local dur = to_int(state.durationMs)
  if dur and dur > 0 then
    values["audioTrackData.totalTime"] = dur // 1000
    local pos = to_int(state.positionMs)
    if pos and pos >= 0 then values["audioTrackData.elapsedTime"] = pos // 1000 end
  end

  return values
end

local function deep_equal(a, b)
  if a == b then return true end
  if type(a) ~= "table" or type(b) ~= "table" then return false end
  for k, v in pairs(a) do
    if not deep_equal(v, b[k]) then return false end
  end
  for k in pairs(b) do
    if a[k] == nil then return false end
  end
  return true
end
M.deep_equal = deep_equal

local function copy(v)
  if type(v) ~= "table" then return v end
  local out = {}
  for k, x in pairs(v) do out[k] = copy(x) end
  return out
end

--- Compute the descriptors to emit and update `snapshot` (last emitted values) in place.
---
--- @param snapshot table last emitted values (mutated)
--- @param values table output of M.normalize
--- @param force boolean|nil emit every known value even if unchanged (refresh)
--- @return table list of { key, capability, attribute, value }
function M.diff(snapshot, values, force)
  local events = {}
  for _, spec in ipairs(M.ORDER) do
    local v = values[spec.key]
    if v ~= nil and (force or not deep_equal(snapshot[spec.key], v)) then
      snapshot[spec.key] = copy(v)
      events[#events + 1] = {
        key = spec.key,
        capability = spec.capability,
        attribute = spec.attribute,
        value = copy(v),
      }
    end
  end
  return events
end

--- Convenience: normalize + diff.
function M.events_for_state(snapshot, state, opts)
  return M.diff(snapshot, M.normalize(state, opts), opts and opts.force)
end

return M
