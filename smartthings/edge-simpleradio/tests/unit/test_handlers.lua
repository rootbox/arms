-- handlers.send_command / refresh with a fake HTTP client, fake session and a fake device
-- thread (timers fired by hand). No lua_libs needed: `st.capabilities` and `log` are stubbed.
--
-- Background (docs/ISSUES_2026-10-12.md §1): the driver used to swallow failures (no event, no
-- re-read -> the app's switch spun and fell back to OFF) and trust a 200 (optimistic ON even
-- when the player did nothing). Now: failure -> immediate GET /state + forced re-emit (or the
-- last snapshot if that fails too); success -> no optimistic switch/playback, verification
-- GET /state after VERIFY_DELAY_S that re-emits the commanded attributes.
--
-- Real: simpleradio.handlers / commands / emitter / state_map / policy / fields.

local t = require "t"

-- ---- stubs installed before handlers is required -----------------------------------------
package.preload["st.capabilities"] = function()
  return setmetatable({}, { __index = function(_, cap)
    return setmetatable({ ID = cap }, { __index = function(_, attr)
      return function(v) return { capability = cap, attribute = attr, value = { value = v } } end
    end })
  end })
end

local logged = {}
package.preload["log"] = function()
  local L = {}
  for _, lvl in ipairs({ "trace", "debug", "info", "warn", "error" }) do
    L[lvl] = function(msg) logged[#logged + 1] = lvl .. " " .. tostring(msg) end
    L[lvl .. "_with"] = function(_, msg) logged[#logged + 1] = lvl .. " " .. tostring(msg) end
  end
  return L
end

local fake_client = {}
function fake_client:command(ip, port, token, body, timeout)
  self.command_calls = self.command_calls + 1
  self.last_timeout = timeout
  self.last_body = body
  return table.unpack(self.command_result, 1, 3)
end
function fake_client:state(ip, port, token)
  self.state_calls = self.state_calls + 1
  return table.unpack(self.state_result, 1, 3)
end

local fake_api = { paired = true }
package.preload["simpleradio.api"] = function()
  return {
    client = function() return fake_client end,
    target = function()
      if fake_api.paired then return "192.168.0.77", 8765, "tok" end
      return "192.168.0.77", 8765, nil
    end,
  }
end

local session_log = {}
package.preload["simpleradio.session"] = function()
  local M = { PAIR_HINT = "hint" }
  for _, name in ipairs({ "kick", "on_unauthorized", "note_failure", "note_state", "stop", "start" }) do
    M[name] = function() session_log[#session_log + 1] = name end
  end
  return M
end

package.preload["simpleradio.discovery"] = function() return { take_pending = function() end } end

local handlers = require "simpleradio.handlers"
local policy = require "simpleradio.policy"
local emitter = require "simpleradio.emitter"

-- ---- fake device ---------------------------------------------------------------------------
local function new_device(id)
  local d = { label = "Simple Radio", id = id or "dev-1", fields = {}, events = {}, timers = {} }
  function d:get_field(k) return self.fields[k] end
  function d:set_field(k, v) self.fields[k] = v end
  function d:emit_event(ev) self.events[#self.events + 1] = ev end
  d.thread = {
    call_with_delay = function(_, delay, fn, name)
      local timer = { delay = delay, fn = fn, name = name, cancelled = false }
      d.timers[#d.timers + 1] = timer
      return timer
    end,
    cancel_timer = function(_, timer) timer.cancelled = true end,
  }
  return d
end

local function pending(device)
  local out = {}
  for _, tm in ipairs(device.timers) do
    if not tm.cancelled and not tm.fired then out[#out + 1] = tm end
  end
  return out
end

local function fire(timer)
  timer.fired = true
  timer.fn()
end

local function values_of(device, attribute)
  local out = {}
  for _, ev in ipairs(device.events) do
    if ev.attribute == attribute then out[#out + 1] = ev.value.value end
  end
  return out
end

local function attrs_of(device)
  local out = {}
  for _, ev in ipairs(device.events) do out[#out + 1] = ev.attribute end
  return out
end

local OFF = { power = "off", playback = "stopped", volume = 20, muted = false, title = "KBS" }
local ON = { power = "on", playback = "playing", volume = 20, muted = false, title = "KBS" }

--- Fresh device whose snapshot already reflects `shown` (what the app currently displays).
local function device_showing(shown)
  local d = new_device()
  emitter.apply(d, shown)
  d.events = {}
  return d
end

local function reset()
  fake_client.command_calls, fake_client.state_calls = 0, 0
  fake_client.command_result, fake_client.state_result = { true }, { nil, "no state configured" }
  fake_client.last_timeout, fake_client.last_body = nil, nil
  fake_api.paired = true
  session_log = {}
  logged = {}
end

-- ---- tests ---------------------------------------------------------------------------------

t.test("command timeout >= 8 s (host's own execute budget is 5 s) and is passed to the client", function()
  reset()
  t.ok(policy.COMMAND_TIMEOUT_S >= 8, "COMMAND_TIMEOUT_S=" .. tostring(policy.COMMAND_TIMEOUT_S))
  local d = device_showing(OFF)
  handlers.send_command({}, d, { command = "on" })
  t.eq(fake_client.last_timeout, policy.COMMAND_TIMEOUT_S)
end)

t.test("on + network timeout -> failure counted, state re-read, full forced re-emit (switch reverts)", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { nil, "timeout", nil }
  fake_client.state_result = { OFF }

  local ok = handlers.send_command({}, d, { command = "on" })

  t.eq(ok, false)
  t.eq(fake_client.state_calls, 1, "GET /state right after the failure")
  t.eq(values_of(d, "switch"), { "off" }, "real state re-emitted even though unchanged")
  t.eq(attrs_of(d), { "switch", "playbackStatus", "volume", "mute", "audioTrackData" })
  t.eq(session_log, { "note_failure", "note_state" })
  t.eq(#pending(d), 0, "no verification scheduled for a failed command")
end)

t.test("on + HTTP 503 / 504 -> counted as a connection failure, state re-read", function()
  for _, status in ipairs({ 503, 504 }) do
    reset()
    local d = device_showing(OFF)
    fake_client.command_result = { nil, "command: HTTP " .. status, status }
    fake_client.state_result = { OFF }
    handlers.send_command({}, d, { command = "on" })
    t.eq(fake_client.state_calls, 1, status .. ": re-read")
    t.eq(values_of(d, "switch"), { "off" }, status .. ": switch")
    t.eq(session_log, { "note_failure", "note_state" }, status .. ": session")
  end
end)

t.test("on + HTTP 500 / 400 -> state re-read, but not counted as a connection failure", function()
  for _, status in ipairs({ 500, 400 }) do
    reset()
    local d = device_showing(OFF)
    fake_client.command_result = { nil, "command: HTTP " .. status, status }
    fake_client.state_result = { OFF }
    handlers.send_command({}, d, { command = "on" })
    t.eq(fake_client.state_calls, 1, status .. ": re-read")
    t.eq(values_of(d, "switch"), { "off" }, status .. ": switch")
    t.eq(session_log, { "note_state" }, status .. ": session")
  end
end)

t.test("failure and the re-read fails too -> last snapshot re-emitted, both failures counted", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { nil, "timeout", nil }
  fake_client.state_result = { nil, "timeout", nil }

  handlers.send_command({}, d, { command = "on" })

  t.eq(fake_client.state_calls, 1)
  t.eq(values_of(d, "switch"), { "off" }, "snapshot value re-emitted")
  t.eq(values_of(d, "playbackStatus"), { "stopped" })
  t.eq(#d.events, 5, "every known attribute")
  t.eq(session_log, { "note_failure", "note_failure" })
end)

t.test("failure with nothing ever emitted (fresh after restart) -> no events, no error", function()
  reset()
  local d = new_device()
  fake_client.command_result = { nil, "connect 192.168.0.77:8765: connection refused", nil }
  fake_client.state_result = { nil, "connect 192.168.0.77:8765: connection refused", nil }
  t.eq(handlers.send_command({}, d, { command = "on" }), false)
  t.eq(#d.events, 0)
end)

t.test("on + 401 -> token dropped, no re-read (it would 401 too), snapshot re-emitted", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { nil, "command: HTTP 401 unauthorized", 401 }
  handlers.send_command({}, d, { command = "on" })
  t.eq(fake_client.state_calls, 0)
  t.eq(session_log, { "on_unauthorized" })
  t.eq(values_of(d, "switch"), { "off" })
end)

t.test("on + 200 -> NO optimistic switch; verification GET /state scheduled after VERIFY_DELAY_S", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { true }
  fake_client.state_result = { ON }

  local ok = handlers.send_command({}, d, { command = "on" })

  t.eq(ok, true)
  t.eq(#d.events, 0, "nothing emitted before verification")
  t.eq(fake_client.state_calls, 0, "verification is delayed, not immediate")
  local timers = pending(d)
  t.eq(#timers, 1)
  t.eq(timers[1].delay, policy.VERIFY_DELAY_S)
  t.eq(timers[1].delay, 1.5)

  fire(timers[1])
  t.eq(fake_client.state_calls, 1)
  t.eq(values_of(d, "switch"), { "on" })
  t.eq(values_of(d, "playbackStatus"), { "playing" })
  t.eq(#d.events, 2, "unchanged volume/mute/track are not re-emitted")
  t.eq(session_log, { "note_state" })
  t.eq(#pending(d), 0)
end)

t.test("on + 200 but the host did nothing -> verification re-emits the real OFF (app leaves pending)", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { true }
  fake_client.state_result = { OFF }
  handlers.send_command({}, d, { command = "on" })
  fire(pending(d)[1])
  t.eq(values_of(d, "switch"), { "off" }, "commanded attribute emitted even though unchanged")
  t.eq(values_of(d, "playbackStatus"), { "stopped" })
  t.eq(#d.events, 2)
end)

t.test("on + 200, verification read fails -> snapshot re-emitted, failure counted", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { true }
  fake_client.state_result = { nil, "timeout", nil }
  handlers.send_command({}, d, { command = "on" })
  fire(pending(d)[1])
  t.eq(values_of(d, "switch"), { "off" })
  t.eq(session_log, { "note_failure" })
end)

t.test("play / pause / stop / off: no optimistic playbackStatus, verified instead", function()
  for _, cmd in ipairs({ "play", "pause", "stop", "off" }) do
    reset()
    local d = device_showing(cmd == "off" and ON or OFF)
    fake_client.command_result = { true }
    handlers.send_command({}, d, { command = cmd })
    t.eq(#d.events, 0, cmd .. ": no optimistic event")
    t.eq(#pending(d), 1, cmd .. ": verification scheduled")
  end
end)

t.test("setVolume / mute / unmute stay optimistic (immediate event) and are still verified", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { true }
  handlers.send_command({}, d, { command = "setVolume", value = 55 })
  t.eq(values_of(d, "volume"), { 55 })
  t.eq(#pending(d), 1)

  reset()
  d = device_showing(OFF)
  handlers.send_command({}, d, { command = "mute" })
  t.eq(values_of(d, "mute"), { "muted" })
  handlers.send_command({}, d, { command = "unmute" })
  t.eq(values_of(d, "mute"), { "muted", "unmuted" })
  t.eq(#pending(d), 1, "one verification per device: the newer command replaces the older timer")
end)

t.test("verification after setVolume re-emits the real volume if the host applied another value", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { true }
  fake_client.state_result = { { power = "off", playback = "stopped", volume = 50, muted = false, title = "KBS" } }
  handlers.send_command({}, d, { command = "setVolume", value = 55 })
  fire(pending(d)[1])
  t.eq(values_of(d, "volume"), { 55, 50 })
  t.eq(#d.events, 2, "only the volume changed")
end)

t.test("a failed command cancels a pending verification and reverts immediately", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { true }
  handlers.send_command({}, d, { command = "on" })
  local first = pending(d)[1]
  fake_client.command_result = { nil, "timeout", nil }
  fake_client.state_result = { OFF }
  handlers.send_command({}, d, { command = "play" })
  t.eq(first.cancelled, true)
  t.eq(#pending(d), 0)
  t.eq(fake_client.state_calls, 1)
  t.eq(values_of(d, "switch"), { "off" })
end)

t.test("device_removed cancels a pending verification", function()
  reset()
  local d = device_showing(OFF)
  fake_client.command_result = { true }
  handlers.send_command({}, d, { command = "on" })
  handlers.device_removed({}, d)
  t.eq(#pending(d), 0)
  t.eq(session_log, { "stop" })
end)

t.test("refresh -> GET /state, everything re-emitted; failing refresh re-emits the snapshot", function()
  reset()
  local d = device_showing(OFF)
  fake_client.state_result = { OFF }
  handlers.refresh({}, d)
  t.eq(fake_client.state_calls, 1)
  t.eq(#d.events, 5)
  t.eq(session_log, { "note_state", "kick" })

  reset()
  d = device_showing(OFF)
  fake_client.state_result = { nil, "timeout", nil }
  handlers.refresh({}, d)
  t.eq(#d.events, 5, "snapshot re-emitted")
  t.eq(session_log, { "note_failure", "kick" })
end)

t.test("unpaired: nothing sent, session kicked, no verification", function()
  reset()
  fake_api.paired = false
  local d = device_showing(OFF)
  t.eq(handlers.send_command({}, d, { command = "on" }), false)
  t.eq(fake_client.command_calls, 0)
  t.eq(#pending(d), 0)
  t.eq(session_log, { "kick" })
end)

t.run("test_handlers")
