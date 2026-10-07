-- End-to-end driver test with the official SmartThings `integration_test` framework
-- (lua_libs release asset). Run from the driver's src/ directory, e.g. via tests/run.sh:
--
--   cd src && LUA_PATH="<lua_libs>/?.lua;<lua_libs>/?/init.lua;./?.lua;./?/init.lua;../tests/lib/?.lua;;" \
--     lua5.3 ../tests/integration/test_driver_e2e.lua
--
-- What is real: st.driver dispatch, capability handlers, command mapping, the HTTP client and
-- request formatting/parsing, state->event mapping, discovery/device creation.
-- What is faked: `st.mdns` (canned discover() result) and the TCP socket (an in-memory host
-- that records requests and answers per LAN_API.md). The background session task (SSE loop)
-- is not started here because the framework's mocked select() cannot drive long-running
-- sleeps; it is covered by tests/integration/test_client_mock.lua against a real socket.

local test = require "integration_test"
local capabilities = require "st.capabilities"
local dkjson = require "dkjson"
local fake_reader = require "fake_reader"

local ROOT = assert(arg[0]:match("^(.*)/tests/integration/[^/]+$"), "run with an absolute/relative path to this file")

local KNOWN_ID = "11111111-aaaa-4bbb-8ccc-000000000001"
local NEW_ID = "22222222-aaaa-4bbb-8ccc-000000000002"
local TOKEN = "tok-123"

------------------------------------------------------------------------------
-- Fakes
------------------------------------------------------------------------------

local fake = { requests = {} }

local HOST_STATE = {
  power = "on", playback = "buffering", mediaId = "1",
  title = "KBS Cool FM - 정오의 희망곡", artist = "정오의 희망곡",
  albumArtUrl = "http://192.168.0.77:8765/art/station/1.png",
  volume = 40, muted = false,
  presets = {
    { id = "1", name = "KBS Cool FM", imageUrl = "http://192.168.0.77:8765/art/station/1.png" },
    { id = "2", name = "MBC FM4U", imageUrl = "http://192.168.0.77:8765/art/station/2.png" },
  },
  updatedAtMs = 1791358000000,
}

local function http_response(status, obj)
  local body = dkjson.encode(obj)
  return string.format("HTTP/1.1 %d X\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%s",
    status, #body, body)
end

local function default_respond(req)
  if req.headers.authorization ~= "Bearer " .. TOKEN then
    return http_response(401, { error = "unauthorized" })
  end
  if req.method == "POST" and req.path == "/api/v1/command" then
    return http_response(200, { ok = true })
  elseif req.method == "GET" and req.path == "/api/v1/state" then
    return http_response(200, HOST_STATE)
  end
  return http_response(404, { error = "not_found" })
end
fake.respond = default_respond

local function parse_request(raw)
  local head, body = raw:match("^(.-)\r\n\r\n(.*)$")
  local lines = {}
  for line in (head .. "\r\n"):gmatch("(.-)\r\n") do lines[#lines + 1] = line end
  local method, path = lines[1]:match("^(%S+) (%S+) HTTP/1%.1$")
  local headers = {}
  for i = 2, #lines do
    local k, v = lines[i]:match("^([^:]+):%s*(.*)$")
    if k then headers[k:lower()] = v end
  end
  return { method = method, path = path, headers = headers, body = body,
    json = body ~= "" and dkjson.decode(body) or nil }
end

local FakeSocket = { gettime = function() return os.time() end }
function FakeSocket.tcp()
  local s = { sent = "" }
  function s:settimeout() return 1 end
  function s:connect(ip, port)
    self.ip, self.port = ip, port
    return 1
  end
  function s:send(data, i)
    self.sent = self.sent .. data:sub(i or 1)
    return #data
  end
  function s:receive(pattern)
    if not self.reader then
      local req = parse_request(self.sent)
      req.ip, req.port = self.ip, self.port
      fake.requests[#fake.requests + 1] = req
      self.reader = fake_reader.new(fake.respond(req))
    end
    return self.reader(pattern)
  end
  function s:close() end
  return s
end

package.preload["simpleradio.transport"] = function()
  return { socket = FakeSocket, json = require "st.json" }
end

fake.mdns_response = { found = {} }
package.preload["st.mdns"] = function()
  return { discover = function() return fake.mdns_response end }
end

-- Real session module, minus the background task.
fake.session_calls = {}
package.preload["simpleradio.session"] = function()
  local real = dofile(ROOT .. "/src/simpleradio/session.lua")
  real.start = function(_, device) table.insert(fake.session_calls, { "start", device.id }) end
  real.stop = function(device) table.insert(fake.session_calls, { "stop", device.id }) end
  local kick = real.kick
  real.kick = function(device)
    table.insert(fake.session_calls, { "kick", device.id })
    return kick(device)
  end
  return real
end

------------------------------------------------------------------------------
-- Mock device
------------------------------------------------------------------------------

-- get_profile_definition resolves profiles/ relative to a caller inside src/
local load_profile = load("return require('integration_test.utils').get_profile_definition(...)",
  "@" .. ROOT .. "/src/_profile_loader.lua")
local profile = load_profile("simpleradio.yml")

local mock_device = test.mock_device.build_test_generic_device({
  profile = profile,
  device_network_id = KNOWN_ID,
  label = "Simple Radio",
})

local function expect_static_events()
  test.socket.capability:__expect_send(mock_device:generate_test_message("main",
    capabilities.mediaPlayback.supportedPlaybackCommands({ "play", "pause", "stop" })))
  test.socket.capability:__expect_send(mock_device:generate_test_message("main",
    capabilities.mediaTrackControl.supportedTrackControlCommands({ "nextTrack", "previousTrack" })))
end

local function init_paired()
  fake.requests = {}
  fake.session_calls = {}
  fake.respond = default_respond
  mock_device:set_field("ip", "192.168.0.77", { persist = true })
  mock_device:set_field("port", 8765, { persist = true })
  mock_device:set_field("token", TOKEN, { persist = true })
  test.mock_device.add_test_device(mock_device)
  expect_static_events()
end

local function init_unpaired()
  fake.requests = {}
  fake.session_calls = {}
  fake.respond = default_respond
  mock_device:set_field("ip", "192.168.0.77", { persist = true })
  test.mock_device.add_test_device(mock_device)
  expect_static_events()
end

test.set_test_init_function(init_paired)

local function driver_device()
  return test.driver_wrapper.driver_under_test:get_device_info(mock_device.id)
end

local function last_request()
  return fake.requests[#fake.requests]
end

local function assert_eq(a, b, msg)
  if a ~= b then error(string.format("%s: expected %s, got %s", msg or "", tostring(b), tostring(a)), 2) end
end

------------------------------------------------------------------------------
-- Tests
------------------------------------------------------------------------------

test.register_coroutine_test(
  "discovery: mDNS TXT id -> new LAN device; known id is skipped but its IP is refreshed",
  function()
    fake.mdns_response = { found = {
      {
        service_info = { name = "Simple Radio SM-T220", service_type = "_simpleradio._tcp", domain = "local" },
        host_info = { name = "tab-a.local", address = "192.168.0.50", port = 8765 },
        txt = { text = { "id=" .. NEW_ID, "v=1", "port=8765", "name=주방 태블릿" } },
      },
      {
        service_info = { name = "Simple Radio SM-X200", service_type = "_simpleradio._tcp", domain = "local" },
        host_info = { name = "tab-b.local", address = "192.168.0.99", port = 8765 },
        txt = { text = { "id=" .. KNOWN_ID, "v=1", "port=8765", "name=거실 태블릿" } },
      },
    } }
    test.mock_devices_api.__expect_create_device({
      type = "LAN",
      deviceNetworkId = NEW_ID,
      label = "Simple Radio",
      profileReference = "simpleradio",
      manufacturer = "Simple Radio",
      model = "Simple Radio Android host",
      vendorProvidedLabel = "주방 태블릿",
    })
    test.wait_for_events()
    local discovery = require "simpleradio.discovery"
    local calls = 0
    discovery.handler(test.driver_wrapper.driver_under_test, {}, function()
      calls = calls + 1
      return calls == 1
    end)
    assert_eq(driver_device():get_field("ip"), "192.168.0.99", "known device IP refreshed")
    local pending = discovery.take_pending(NEW_ID)
    assert(pending and pending.ip == "192.168.0.50", "address of the new device kept for `added`")
  end
)

test.register_coroutine_test(
  "switch on -> POST /api/v1/command {command:on} with Bearer token, optimistic switch event",
  function()
    test.wait_for_events()
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "switch", component = "main", command = "on", args = {} } })
    test.socket.capability:__expect_send(mock_device:generate_test_message("main", capabilities.switch.switch.on()))
    test.wait_for_events()
    local req = last_request()
    assert(req, "no HTTP request made")
    assert_eq(req.method, "POST", "method")
    assert_eq(req.path, "/api/v1/command", "path")
    assert_eq(req.ip, "192.168.0.77", "ip")
    assert_eq(req.port, 8765, "port")
    assert_eq(req.headers.authorization, "Bearer " .. TOKEN, "auth")
    assert_eq(req.headers.host, "192.168.0.77:8765", "host header")
    assert_eq(req.json.command, "on", "command")
    assert_eq(req.json.value, nil, "no value")
  end
)

test.register_coroutine_test(
  "setVolume(55) -> {command:setVolume, value:55}; volume event",
  function()
    test.wait_for_events()
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "audioVolume", component = "main", command = "setVolume", args = { 55 } } })
    test.socket.capability:__expect_send(mock_device:generate_test_message("main", capabilities.audioVolume.volume(55)))
    test.wait_for_events()
    local req = last_request()
    assert_eq(req.json.command, "setVolume", "command")
    assert_eq(req.json.value, 55, "value")
  end
)

test.register_coroutine_test(
  "setMute(muted) -> {command:mute}; mute event",
  function()
    test.wait_for_events()
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "audioMute", component = "main", command = "setMute", args = { "muted" } } })
    test.socket.capability:__expect_send(mock_device:generate_test_message("main", capabilities.audioMute.mute.muted()))
    test.wait_for_events()
    assert_eq(last_request().json.command, "mute", "command")
  end
)

test.register_coroutine_test(
  "playPreset / nextTrack: forwarded, no optimistic event",
  function()
    test.wait_for_events()
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "mediaPresets", component = "main", command = "playPreset", args = { "2" } } })
    test.wait_for_events()
    assert_eq(last_request().json.command, "playPreset", "command")
    assert_eq(last_request().json.value, "2", "preset id")
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "mediaTrackControl", component = "main", command = "nextTrack", args = {} } })
    test.wait_for_events()
    assert_eq(last_request().json.command, "next", "command")
    assert_eq(#fake.requests, 2, "requests")
  end
)

test.register_coroutine_test(
  "refresh -> GET /api/v1/state -> every attribute emitted (buffering shown as playing)",
  function()
    test.wait_for_events()
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "refresh", component = "main", command = "refresh", args = {} } })
    local function expect(ev)
      test.socket.capability:__expect_send(mock_device:generate_test_message("main", ev))
    end
    expect(capabilities.switch.switch.on())
    expect(capabilities.mediaPlayback.playbackStatus.playing())
    expect(capabilities.audioVolume.volume(40))
    expect(capabilities.audioMute.mute.unmuted())
    expect(capabilities.mediaPresets.presets(HOST_STATE.presets))
    expect(capabilities.audioTrackData.audioTrackData({
      title = HOST_STATE.title, artist = HOST_STATE.artist, albumArtUrl = HOST_STATE.albumArtUrl,
    }))
    test.wait_for_events()
    assert_eq(last_request().method, "GET", "method")
    assert_eq(last_request().path, "/api/v1/state", "path")
  end
)

test.register_coroutine_test(
  "401 on a command drops the stored token and keeps state",
  function()
    test.wait_for_events()
    fake.respond = function() return http_response(401, { error = "unauthorized" }) end
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "mediaPlayback", component = "main", command = "pause", args = {} } })
    test.wait_for_events()
    assert_eq(last_request().path, "/api/v1/command", "path")
    assert_eq(driver_device():get_field("token"), nil, "token dropped")
  end
)

test.register_coroutine_test(
  "unpaired: commands are not sent; refresh re-arms pairing (kick)",
  function()
    test.wait_for_events()
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "switch", component = "main", command = "off", args = {} } })
    test.socket.capability:__queue_receive({ mock_device.id,
      { capability = "refresh", component = "main", command = "refresh", args = {} } })
    test.wait_for_events()
    assert_eq(#fake.requests, 0, "no HTTP while unpaired")
    local kicks = 0
    for _, c in ipairs(fake.session_calls) do if c[1] == "kick" then kicks = kicks + 1 end end
    assert_eq(kicks, 2, "kicks")
  end,
  { test_init = init_unpaired }
)

test.register_coroutine_test(
  "init starts the session; removed stops it",
  function()
    test.wait_for_events()
    test.socket.device_lifecycle:__queue_receive({ mock_device.id, "removed" })
    test.wait_for_events()
    local seen = {}
    for _, c in ipairs(fake.session_calls) do seen[#seen + 1] = c[1] end
    assert_eq(table.concat(seen, ","), "start,stop", "session calls")
  end
)

test.run_registered_tests()
