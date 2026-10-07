-- Integration: simpleradio.client (+ http, sse, commands, state_map) over real TCP
-- against tests/mock_host.py, using plain LuaSocket + dkjson (the hub uses cosock + st.json,
-- which expose the same API).
--
--   MOCK_PORT=<port> lua5.3 tests/integration/test_client_mock.lua

local t = require "t"
local socket = require "socket"
local json = require "dkjson"

local Client = require "simpleradio.client"
local commands = require "simpleradio.commands"
local state_map = require "simpleradio.state_map"

local IP = "127.0.0.1"
local PORT = tonumber(os.getenv("MOCK_PORT") or "")
assert(PORT, "MOCK_PORT not set")

local client = Client.new({ socket = socket, json = json, timeout = 5 })

-- test-control helper (same client, mock-only endpoints)
local function control(path, body)
  local res, err = client:request(IP, PORT, body and "POST" or "GET", "/_mock/" .. path, { body = body })
  assert(res, err)
  assert(res.status == 200, "control " .. path .. " -> " .. tostring(res.status))
  return res.json
end

local token

t.test("info: unauthenticated, apiVersion 1, pairing closed", function()
  local info, err = client:info(IP, PORT)
  t.ok(info, err)
  t.eq(info.apiVersion, 1)
  t.eq(info.pairingOpen, false)
end)

t.test("pair: 403 pairing_closed while the window is closed", function()
  local tok, err, status, code = client:pair(IP, PORT, "Simple Radio")
  t.is_nil(tok)
  t.eq(status, 403)
  t.eq(code, "pairing_closed")
  t.match(err, "pairing_closed")
end)

t.test("pair: token once the tablet opens the window", function()
  control("pairing", { open = true })
  t.eq(client:info(IP, PORT).pairingOpen, true)
  local tok, err = client:pair(IP, PORT, "Simple Radio")
  t.ok(tok, err)
  token = tok
end)

t.test("state: 401 without / with a wrong token", function()
  local s, _, status = client:state(IP, PORT, nil)
  t.is_nil(s)
  t.eq(status, 401)
  s, _, status = client:state(IP, PORT, "wrong")
  t.eq(status, 401)
end)

t.test("state: full state maps to capability values", function()
  local s, err = client:state(IP, PORT, token)
  t.ok(s, err)
  local values = state_map.normalize(s)
  t.eq(values["switch.switch"], "off")
  t.eq(values["audioVolume.volume"], 40)
  t.eq(#values["mediaPresets.presets"], 3)
  t.match(values["mediaPresets.presets"][1].imageUrl, "/art/station/1%.png$")
end)

t.test("command: capability commands reach the host as LAN_API commands", function()
  local before = #control("commands")
  local sequence = {
    { "audioVolume", "setVolume", { volume = 55 } },
    { "audioMute", "setMute", { state = "muted" } },
    { "mediaPresets", "playPreset", { presetId = "2" } },
    { "mediaTrackControl", "nextTrack", {} },
    { "switch", "off", {} },
  }
  for _, c in ipairs(sequence) do
    local body = assert(commands.to_request(c[1], c[2], c[3]))
    local ok, err = client:command(IP, PORT, token, body)
    t.ok(ok, c[2] .. ": " .. tostring(err))
  end
  local log = control("commands")
  t.eq(#log - before, 5)
  t.eq(log[before + 1], { command = "setVolume", value = 55, hasValue = true })
  t.eq(log[before + 2], { command = "mute", hasValue = false })
  t.eq(log[before + 3], { command = "playPreset", value = "2", hasValue = true })
  t.eq(log[before + 4], { command = "next", hasValue = false })
  t.eq(log[before + 5], { command = "off", hasValue = false })
  local s = client:state(IP, PORT, token)
  t.eq(s.volume, 55)
  t.eq(s.muted, true)
  t.eq(s.mediaId, "3")
  t.eq(s.power, "off")
end)

t.test("command: host 400 is reported as failure with message", function()
  local ok, err, status = client:command(IP, PORT, token, { command = "playPreset", value = "99" })
  t.is_nil(ok)
  t.eq(status, 400)
  t.match(err, "unknown preset")
end)

local function sse_roundtrip(chunked)
  control("config", { sse_chunked = chunked, ping_interval = 0.2 })
  local stream, err, status = client:open_events(IP, PORT, token, 5)
  t.ok(stream, tostring(err) .. " " .. tostring(status))
  t.eq(stream.head.headers["transfer-encoding"] ~= nil, chunked, "framing")

  -- 1) current state right after connecting
  local ev = assert(stream:next())
  t.eq(ev.event, "state")
  local snap = {}
  local first = state_map.events_for_state(snap, (json.decode(ev.data)))
  t.ok(#first >= 5, "initial state emits everything")

  -- 2) pings (": ping") are swallowed; a state change is pushed
  socket.sleep(0.5)
  control("state", { volume = chunked and 66 or 77, title = "변경된 제목 " .. tostring(chunked) })
  ev = assert(stream:next())
  t.eq(ev.event, "state")
  local changes = state_map.events_for_state(snap, (json.decode(ev.data)))
  local keys = {}
  for _, c in ipairs(changes) do keys[#keys + 1] = c.key end
  table.sort(keys)
  t.eq(keys, { "audioTrackData.audioTrackData", "audioVolume.volume" })

  -- 3) a command also produces an SSE update
  local mute_cmd = snap["audioMute.mute"] == "muted" and "unmute" or "mute"
  assert(client:command(IP, PORT, token, { command = mute_cmd }))
  ev = assert(stream:next())
  local changes2 = state_map.events_for_state(snap, (json.decode(ev.data)))
  t.eq(#changes2, 1)
  t.eq(changes2[1].value, mute_cmd == "mute" and "muted" or "unmuted")
  stream:close()
end

t.test("SSE (unframed, Connection: close): initial state, ping, pushed change", function()
  sse_roundtrip(false)
end)

t.test("SSE (chunked transfer encoding): same behaviour", function()
  sse_roundtrip(true)
end)

t.test("SSE idle timeout surfaces as 'timeout'", function()
  control("config", { sse_chunked = false, ping_interval = 30 })
  local stream = assert(client:open_events(IP, PORT, token, 0.5))
  assert(stream:next()) -- initial state
  local ev, err = stream:next()
  t.is_nil(ev)
  t.match(err, "timeout")
  control("config", { ping_interval = 0.2 })
end)

t.test("revoked token: events and commands answer 401", function()
  control("revoke", {})
  local stream, _, status = client:open_events(IP, PORT, token, 2)
  t.is_nil(stream)
  t.eq(status, 401)
  local ok, _, cstatus = client:command(IP, PORT, token, { command = "play" })
  t.is_nil(ok)
  t.eq(cstatus, 401)
end)

t.test("network error: closed port gives status nil", function()
  local srv = assert(socket.bind("127.0.0.1", 0))
  local _, free_port = srv:getsockname()
  srv:close()
  local s, err, status = client:state(IP, tonumber(free_port), "x")
  t.is_nil(s)
  t.is_nil(status)
  t.match(err, "connect")
end)

t.run("test_client_mock")
