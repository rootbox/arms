local t = require "t"
local http = require "simpleradio.http"
local fake = require "fake_reader"

local function chunk(data, ext)
  return string.format("%x", #data) .. (ext or "") .. "\r\n" .. data .. "\r\n"
end

t.test("build_request: GET with auth, host:port, Connection close", function()
  local req = http.build_request("GET", "/api/v1/state", {
    host = "192.168.0.10", port = 8765, headers = { Authorization = "Bearer abc" },
  })
  t.match(req, "^GET /api/v1/state HTTP/1%.1\r\n")
  t.match(req, "\r\nHost: 192%.168%.0%.10:8765\r\n")
  t.match(req, "\r\nAuthorization: Bearer abc\r\n")
  t.match(req, "\r\nConnection: close\r\n")
  t.match(req, "\r\n\r\n$")
  t.ok(not req:find("Content%-Length"), "no body -> no Content-Length on GET")
end)

t.test("build_request: POST JSON body with byte length (UTF-8)", function()
  local body = '{"label":"거실"}'
  local req = http.build_request("POST", "/api/v1/pair", { host = "h", port = 8765, body = body })
  t.match(req, "\r\nContent%-Type: application/json; charset=utf%-8\r\n")
  t.match(req, "\r\nContent%-Length: " .. #body .. "\r\n")
  t.eq(req:sub(-#body), body)
end)

t.test("build_request: caller headers override defaults; CR/LF stripped", function()
  local req = http.build_request("GET", "/x", { host = "h", headers = { Connection = "keep-alive", X = "a\r\nB: c" } })
  t.ok(not req:find("Connection: close"), "no default Connection")
  t.match(req, "Connection: keep%-alive")
  t.match(req, "X: a  B: c\r\n")
end)

t.test("parse_status_line", function()
  t.eq({ http.parse_status_line("HTTP/1.1 200 OK") }, { 200, "OK" })
  t.eq({ http.parse_status_line("HTTP/1.0 403 Forbidden") }, { 403, "Forbidden" })
  t.eq({ http.parse_status_line("HTTP/1.1 204") }, { 204, "" })
  local s, err = http.parse_status_line("garbage")
  t.is_nil(s)
  t.match(err, "malformed")
end)

t.test("read_response: Content-Length body", function()
  local raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}"
  local res = http.read_response(fake.new(raw))
  t.eq(res.status, 200)
  t.eq(res.headers["content-type"], "application/json")
  t.eq(res.body, '{"ok":true}')
end)

t.test("read_response: chunked body", function()
  local raw = "HTTP/1.1 403 Forbidden\r\nTransfer-Encoding: chunked\r\n\r\n" ..
    chunk('{"error":') .. chunk('"pairing_closed"}', ";ext=1") .. "0\r\nX-Trailer: y\r\n\r\n"
  local res = http.read_response(fake.new(raw))
  t.eq(res.status, 403)
  t.eq(res.body, '{"error":"pairing_closed"}')
end)

t.test("read_response: body until close", function()
  local raw = "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nhello\nworld"
  local res = http.read_response(fake.new(raw))
  t.eq(res.body, "hello\nworld")
end)

t.test("read_response: truncated Content-Length body is an error", function()
  local raw = "HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\nshort"
  local res, err = http.read_response(fake.new(raw))
  t.is_nil(res)
  t.match(err, "closed")
end)

t.test("read_response: timeout in headers propagates", function()
  local res, err = http.read_response(fake.new("HTTP/1.1 200 OK\r\nContent-", { err = "timeout" }))
  t.is_nil(res)
  t.match(err, "timeout")
end)

t.test("duplicate headers are joined; names lower-cased", function()
  local head = http.read_head(fake.new("HTTP/1.1 200 OK\r\nX-A: 1\r\nx-a: 2\r\n\r\n"))
  t.eq(head.headers["x-a"], "1, 2")
end)

t.test("body_reader line_mode for an unframed SSE stream", function()
  local raw = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n" ..
    "event: state\r\ndata: {}\r\n\r\n: ping\r\n"
  local read = fake.new(raw)
  local head = http.read_head(read)
  t.ok(http.is_event_stream(head))
  local reader = http.body_reader(head, read, { line_mode = true })
  t.eq(reader(), "event: state\n")
  t.eq(reader(), "data: {}\n")
  t.eq(reader(), "\n")
  t.eq(reader(), ": ping\n")
  t.is_nil(reader(), "EOF on close")
end)

t.test("body_reader chunked stream yields chunk payloads", function()
  local raw = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" ..
    chunk('event: state\ndata: {"a":1}\n\n') .. chunk(": ping\n\n")
  local read = fake.new(raw, { err = "timeout" })
  local head = http.read_head(read)
  local reader = http.body_reader(head, read, { line_mode = true })
  t.eq(reader(), 'event: state\ndata: {"a":1}\n\n')
  t.eq(reader(), ": ping\n\n")
  local piece, err = reader()
  t.is_nil(piece)
  t.match(err, "timeout")
end)

t.test("204 has no body", function()
  local res = http.read_response(fake.new("HTTP/1.1 204 No Content\r\n\r\n"))
  t.eq(res.status, 204)
  t.eq(res.body, "")
end)

t.run("test_http")
