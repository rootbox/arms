-- Turns state_map descriptors into SmartThings capability events (ST layer).

local capabilities = require "st.capabilities"
local log = require "log"

local fields = require "simpleradio.fields"
local state_map = require "simpleradio.state_map"

local M = {}

local function snapshot_of(device)
  local snap = device:get_field(fields.SNAPSHOT)
  if type(snap) ~= "table" then
    snap = {}
    device:set_field(fields.SNAPSHOT, snap)
  end
  return snap
end

function M.to_capability_event(desc)
  local cap = capabilities[desc.capability]
  local attr = cap and cap[desc.attribute]
  if not attr then return nil end
  return attr(desc.value)
end

--- Apply a (full or partial) host state to the device, emitting only changed attributes.
--- @param opts table|nil { partial = bool, force = bool }
--- @return number events emitted
function M.apply(device, state, opts)
  local events = state_map.events_for_state(snapshot_of(device), state, opts)
  local n = 0
  for _, desc in ipairs(events) do
    local ok, err = pcall(function()
      local ev = M.to_capability_event(desc)
      if ev then
        device:emit_event(ev)
        n = n + 1
      end
    end)
    if not ok then
      log.warn(string.format("[%s] emit %s failed: %s", device.label, desc.key, tostring(err)))
      -- forget the value so the next update retries it
      snapshot_of(device)[desc.key] = nil
    end
  end
  return n
end

--- Forget what was emitted so the next state re-emits everything.
function M.reset(device)
  device:set_field(fields.SNAPSHOT, {})
end

--- Static attributes (emitted on init).
function M.emit_static(device)
  device:emit_event(capabilities.mediaPlayback.supportedPlaybackCommands({
    capabilities.mediaPlayback.commands.play.NAME,
    capabilities.mediaPlayback.commands.pause.NAME,
    capabilities.mediaPlayback.commands.stop.NAME,
  }))
  device:emit_event(capabilities.mediaTrackControl.supportedTrackControlCommands({
    capabilities.mediaTrackControl.commands.nextTrack.NAME,
    capabilities.mediaTrackControl.commands.previousTrack.NAME,
  }))
end

return M
