-- Integration: the real background session (simpleradio.session) running under the cosock
-- scheduler with real LuaSocket TCP against tests/mock_host.py.
-- cosock comes from the upstream `cosock` rock (luarocks install cosock): the hub build in
-- lua_libs needs hub-native timers (_envlibrequire("timer")) and cannot run off-hub.
-- `log` comes from lua_libs (falls back to stdout_log off-hub).
-- The SmartThings device object, `st.mdns` and the event emitter are faked; policy timings
-- are shrunk so the whole lifecycle runs in a few seconds.
--
--   MOCK_PORT=<port> LUA_PATH="<rocks>;<lua_libs>;src;tests/lib" lua5.3 tests/integration/test_session_mock.lua
-- (rocks first, so `socket` and `cosock` are the native LuaSocket / upstream cosock)
--
-- Covers: mDNS resolve by id -> pairing retries while closed (offline) -> token once the window
-- opens -> SSE connect (online) -> pushed change -> stream drop + 401 -> token dropped and
-- re-paired -> SSE refused -> polling fallback -> mDNS re-resolve after connection failures
-- -> stop.

local cosock = require "cosock"
local csocket = require "cosock.socket"
local dkjson = require "dkjson"

local PORT = tonumber(os.getenv("MOCK_PORT") or "")
assert(PORT, "MOCK_PORT not set")
local ID = "33333333-aaaa-4bbb-8ccc-000000000003"

-- ---------------------------------------------------------------- fakes
local mdns_host = { address = "127.0.0.1", port = PORT }
local mdns_calls = 0
package.preload["st.mdns"] = function()
  return {
    discover = function()
      mdns_calls = mdns_calls + 1
      return { found = { {
        service_info = { name = "Simple Radio Mock", service_type = "_simpleradio._tcp", domain = "local" },
        host_info = { name = "mock.local", address = mdns_host.address, port = mdns_host.port },
        txt = { text = { "id=" .. ID, "v=1", "port=" .. tostring(mdns_host.port), "name=Mock Tablet" } },
      } } }
    end,
  }
end

package.preload["simpleradio.transport"] = function()
  return { socket = csocket, json = dkjson }
end

local state_map = require "simpleradio.state_map"
local emitted = {}
package.preload["simpleradio.emitter"] = function()
  local snap = {}
  return {
    apply = function(_, state, opts)
      local evs = state_map.events_for_state(snap, state, opts)
      for _, e in ipairs(evs) do emitted[#emitted + 1] = e end
      return #evs
    end,
    emit_static = function() end,
    reset = function() snap = {} end,
  }
end

local policy = require "simpleradio.policy"
policy.PAIR_INTERVAL_S = 0.2
policy.PAIR_WINDOW_S = 30
policy.DORMANT_INFO_POLL_S = 0.2
policy.SSE_IDLE_TIMEOUT_S = 3
policy.POLL_INTERVAL_S = 0.2
policy.SSE_RETRY_WHILE_POLLING_S = 1
policy.HEALTH_CHECK_S = 0.1
policy.STALE_STATE_S = 1
policy.BACKOFF_S = { 0.1, 0.1, 0.2, 0.3 }

local health = {} -- sequence of "online"/"offline"
local device = {
  id = "device-uuid-1",
  device_network_id = ID,
  label = "Simple Radio",
  _fields = {},
}
function device:get_field(k) return self._fields[k] end
function device:set_field(k, v) self._fields[k] = v end
function device:online() health[#health + 1] = "online" end
function device:offline() health[#health + 1] = "offline" end
device.thread = {
  call_on_schedule = function(_, interval, fn)
    local timer = { cancelled = false }
    cosock.spawn(function()
      while not timer.cancelled do
        csocket.sleep(interval)
        if not timer.cancelled then fn() end
      end
    end, "fake health timer")
    return timer
  end,
  cancel_timer = function(_, timer) timer.cancelled = true end,
}

local session = require "simpleradio.session"
local Client = require "simpleradio.client"
local control_client = Client.new({ socket = csocket, json = dkjson, timeout = 5 })

-- ---------------------------------------------------------------- helpers
local results = { passed = 0, failed = 0 }

local function control(path, body)
  local res, err = control_client:request("127.0.0.1", PORT, body and "POST" or "GET", "/_mock/" .. path, { body = body })
  assert(res and res.status == 200, "control " .. path .. ": " .. tostring(err or (res and res.status)))
  return res.json
end

local function eventually(cond, timeout, what)
  local deadline = csocket.gettime() + (timeout or 5)
  while csocket.gettime() < deadline do
    if cond() then return true end
    csocket.sleep(0.05)
  end
  error("timed out waiting for: " .. what, 2)
end

local function last_health() return health[#health] end

local function dead_port()
  local probe = assert(require("socket").bind("127.0.0.1", 0))
  local _, port = probe:getsockname()
  probe:close()
  return tonumber(port)
end

local function emitted_value(key)
  for i = #emitted, 1, -1 do
    if emitted[i].key == key then return emitted[i].value end
  end
end

local function step(name, fn)
  local ok, err = xpcall(fn, debug.traceback)
  if ok then
    results.passed = results.passed + 1
    print("  ok   " .. name)
  else
    results.failed = results.failed + 1
    print("  FAIL " .. name .. "\n" .. tostring(err))
  end
  return ok
end

-- ---------------------------------------------------------------- scenario
cosock.spawn(function()
  control("pairing", { open = false })
  control("config", { ping_interval = 0.3, sse_chunked = true, events_status = 200 })
  local base_attempts = control("stats").pairAttempts

  local ok = step("no IP yet: resolves via mDNS by TXT id; pairing retried while closed; offline", function()
    session.start({}, device)
    eventually(function() return device:get_field("ip") == "127.0.0.1" end, 3, "mDNS resolve")
    eventually(function() return control("stats").pairAttempts - base_attempts >= 3 end, 3, "3 pair attempts")
    assert(device:get_field("token") == nil, "no token while closed")
    assert(last_health() == "offline", "offline while unpaired, got " .. tostring(last_health()))
  end)

  ok = ok and step("window opens: token stored, SSE connected, online, initial state emitted", function()
    control("pairing", { open = true })
    eventually(function() return device:get_field("token") ~= nil end, 3, "token")
    eventually(function() return last_health() == "online" end, 3, "online")
    eventually(function() return emitted_value("switch.switch") ~= nil end, 3, "initial state")
    assert(emitted_value("audioVolume.volume") == 40, "volume 40")
    eventually(function() return control("stats").streams == 1 end, 3, "one SSE stream")
  end)

  ok = ok and step("pushed change -> only the changed attribute is emitted", function()
    local before = #emitted
    control("state", { volume = 63 })
    eventually(function() return #emitted > before end, 3, "change event")
    assert(#emitted == before + 1, "exactly one event, got " .. (#emitted - before))
    assert(emitted[#emitted].key == "audioVolume.volume" and emitted[#emitted].value == 63, "volume 63")
  end)

  ok = ok and step("stream dropped + token revoked -> 401 -> token dropped, re-paired, SSE again", function()
    local old = device:get_field("token")
    control("revoke", {})
    control("drop_streams", {})
    eventually(function()
      local t = device:get_field("token")
      return t ~= nil and t ~= old
    end, 5, "new token")
    eventually(function() return control("stats").streams == 1 end, 3, "SSE reconnected")
    eventually(function() return last_health() == "online" end, 3, "online again")
  end)

  ok = ok and step("SSE refused (404) -> polling /state every interval keeps state fresh and online", function()
    control("config", { events_status = 404 })
    control("drop_streams", {})
    eventually(function() return session.get(device).mode == "poll" end, 3, "poll mode")
    local before = #emitted
    control("state", { muted = true })
    eventually(function() return emitted_value("audioMute.mute") == "muted" end, 3, "mute via polling")
    assert(#emitted == before + 1, "only the change is emitted")
    csocket.sleep(1.2) -- longer than STALE_STATE_S: polling keeps last_state_at fresh
    assert(last_health() == "online", "still online while polling")
  end)

  ok = ok and step("SSE comes back -> session leaves polling mode", function()
    control("config", { events_status = 200 })
    eventually(function() return control("stats").streams == 1 end, 4, "SSE restored")
    assert(session.get(device).mode == "sse", "mode sse")
  end)

  ok = ok and step("stale address: connection failures trigger mDNS re-resolve by id", function()
    local calls_before = mdns_calls
    device:set_field("port", dead_port()) -- stale address: nothing listens there
    control("drop_streams", {})
    eventually(function() return mdns_calls > calls_before end, 6, "mDNS re-resolve")
    eventually(function() return device:get_field("port") == PORT end, 3, "address restored from mDNS")
    eventually(function() return control("stats").streams == 1 end, 4, "reconnected")
    assert(last_health() == "online", "online after recovery")
  end)

  ok = ok and step("host gone (also from mDNS) -> offline once state is older than the stale limit; back online when it returns", function()
    local gone = dead_port()
    mdns_host.port = gone
    device:set_field("port", gone)
    control("drop_streams", {})
    eventually(function() return last_health() == "offline" end, 6, "offline while unreachable")
    mdns_host.port = PORT
    eventually(function() return device:get_field("port") == PORT end, 6, "address restored from mDNS")
    eventually(function() return last_health() == "online" end, 6, "online again")
  end)

  step("stop: session task exits and the stream is released", function()
    session.stop(device)
    eventually(function() return control("stats").streams == 0 end, 3, "stream closed after stop")
  end)

  print(string.format("test_session_mock: %d passed, %d failed", results.passed, results.failed))
  os.exit(results.failed == 0 and 0 or 1)
end, "test main")

cosock.run()
