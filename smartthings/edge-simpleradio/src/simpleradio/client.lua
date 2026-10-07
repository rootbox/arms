-- HTTP client for the Simple Radio host API (LAN_API.md).
--
-- No SmartThings dependencies: the socket library and JSON codec are injected.
--   on the hub : Client.new{ socket = require "cosock.socket", json = require "st.json" }
--   in tests   : Client.new{ socket = require "socket", json = require "dkjson" }
-- Both expose the LuaSocket TCP API (tcp/settimeout/connect/send/receive/close).

local http = require "simpleradio.http"
local sse = require "simpleradio.sse"

local Client = {}
Client.__index = Client

local API = "/api/v1"

function Client.new(opts)
  assert(opts and opts.socket, "socket library required")
  assert(opts.json, "json codec required")
  local self = setmetatable({}, Client)
  self.socket = opts.socket
  self.json = opts.json
  self.timeout = opts.timeout or 5
  self.gettime = opts.gettime or (opts.socket.gettime) or os.time
  return self
end

function Client:decode(body)
  if type(body) ~= "string" or body == "" then return nil end
  local ok, obj = pcall(self.json.decode, body)
  if ok and type(obj) == "table" then return obj end
  return nil
end

function Client:encode(tbl)
  local ok, s = pcall(self.json.encode, tbl)
  if ok and type(s) == "string" then return s end
  return nil
end

local function send_all(sock, data)
  local i = 1
  while i <= #data do
    local last, err, partial = sock:send(data, i)
    if last then
      i = last + 1
    elseif partial and partial >= i then
      i = partial + 1
      if err ~= "timeout" then return nil, err end
    else
      return nil, err
    end
  end
  return true
end

--- Open a TCP connection and send a request.
--- @return sock|nil, string|nil err
function Client:_connect_and_send(ip, port, payload, timeout)
  local sock, err = self.socket.tcp()
  if not sock then return nil, "socket: " .. tostring(err) end
  sock:settimeout(timeout)
  local ok, cerr = sock:connect(ip, port)
  -- cosock retries a non-blocking connect once the socket is writable; with LuaSocket the
  -- retry reports EISCONN ("already connected"), which means the connection succeeded.
  if not ok and cerr ~= "already connected" then
    sock:close()
    return nil, "connect " .. tostring(ip) .. ":" .. tostring(port) .. ": " .. tostring(cerr)
  end
  local sent, serr = send_all(sock, payload)
  if not sent then
    sock:close()
    return nil, "send: " .. tostring(serr)
  end
  return sock
end

local function auth_headers(token, extra)
  local h = extra or {}
  h["Accept"] = h["Accept"] or "application/json"
  if token then h["Authorization"] = "Bearer " .. token end
  return h
end

--- Perform one request/response exchange (Connection: close).
--- @param opts table { token, body (table), timeout }
--- @return table|nil response { status, headers, body, json }
--- @return string|nil error (network level; HTTP errors are returned as responses)
function Client:request(ip, port, method, path, opts)
  opts = opts or {}
  local timeout = opts.timeout or self.timeout
  local body
  if opts.body ~= nil then
    body = self:encode(opts.body)
    if not body then return nil, "json encode failed" end
  end
  local payload = http.build_request(method, path, {
    host = ip, port = port, body = body, headers = auth_headers(opts.token),
  })
  local sock, err = self:_connect_and_send(ip, port, payload, timeout)
  if not sock then return nil, err end

  local deadline = self.gettime() + timeout
  local gettime = self.gettime
  local function read(pattern)
    local remaining = deadline - gettime()
    if remaining <= 0 then return nil, "timeout" end
    sock:settimeout(remaining)
    return sock:receive(pattern)
  end
  local res, rerr = http.read_response(read)
  sock:close()
  if not res then return nil, rerr end
  res.json = self:decode(res.body)
  return res
end

-- Error helper: (nil, message, status, error_code)
local function http_error(res, what)
  local code = res.json and (res.json.error or res.json.message)
  return nil, string.format("%s: HTTP %d%s", what, res.status, code and (" " .. tostring(code)) or ""),
    res.status, res.json and res.json.error
end

--- GET /api/v1/info (no auth)
function Client:info(ip, port)
  local res, err = self:request(ip, port, "GET", API .. "/info")
  if not res then return nil, err end
  if res.status ~= 200 or not res.json then return http_error(res, "info") end
  return res.json
end

--- POST /api/v1/pair -> token
--- @return string|nil token, string|nil err, number|nil status, string|nil error_code ("pairing_closed")
function Client:pair(ip, port, label)
  local res, err = self:request(ip, port, "POST", API .. "/pair", {
    body = { client = "smartthings-edge", label = label or "SmartThings" },
  })
  if not res then return nil, err end
  if res.status == 200 and res.json and type(res.json.token) == "string" and res.json.token ~= "" then
    return res.json.token
  end
  return http_error(res, "pair")
end

--- GET /api/v1/state
--- @return table|nil state, string|nil err, number|nil status
function Client:state(ip, port, token)
  local res, err = self:request(ip, port, "GET", API .. "/state", { token = token })
  if not res then return nil, err end
  if res.status == 200 and res.json then return res.json end
  return http_error(res, "state")
end

--- POST /api/v1/command
--- @param body table { command = "...", value = ... }
--- @return boolean|nil ok, string|nil err, number|nil status
function Client:command(ip, port, token, body, timeout)
  local res, err = self:request(ip, port, "POST", API .. "/command", {
    token = token, body = body, timeout = timeout,
  })
  if not res then return nil, err end
  if res.status == 200 and (res.json == nil or res.json.ok ~= false) then return true end
  if res.status == 200 then
    return nil, "command rejected: " .. tostring(res.json.message), res.status
  end
  return http_error(res, "command")
end

------------------------------------------------------------------------------
-- SSE stream
------------------------------------------------------------------------------

local Stream = {}
Stream.__index = Stream

--- Next SSE event ({ event, data, id }), blocking up to the idle timeout.
--- @param should_stop function|nil checked after every received piece (incl. ": ping"),
---        so a caller can abandon the stream without waiting for a real event
--- @return table|nil event, string|nil err ("timeout", "closed", "stopped", ...)
function Stream:next(should_stop)
  while true do
    if #self.queue > 0 then return table.remove(self.queue, 1) end
    if self.closed then return nil, "closed" end
    if should_stop and should_stop() then return nil, "stopped" end
    local piece, err = self.reader()
    if piece == nil then
      self:close()
      return nil, err or "stream ended"
    end
    for _, ev in ipairs(self.parser:feed(piece)) do
      self.queue[#self.queue + 1] = ev
    end
  end
end

function Stream:close()
  if not self.closed then
    self.closed = true
    pcall(self.sock.close, self.sock)
  end
end

--- GET /api/v1/events (SSE)
--- @param idle_timeout number seconds without any byte (incl. ": ping") before giving up
--- @return Stream|nil stream, string|nil err, number|nil status
function Client:open_events(ip, port, token, idle_timeout)
  idle_timeout = idle_timeout or 60
  local payload = http.build_request("GET", API .. "/events", {
    host = ip, port = port,
    headers = {
      Authorization = token and ("Bearer " .. token) or nil,
      Accept = "text/event-stream",
      ["Cache-Control"] = "no-cache",
      Connection = "keep-alive",
    },
  })
  local sock, err = self:_connect_and_send(ip, port, payload, self.timeout)
  if not sock then return nil, err end

  local head_deadline = self.gettime() + self.timeout
  local gettime = self.gettime
  local in_head = true
  local function read(pattern)
    if in_head then
      local remaining = head_deadline - gettime()
      if remaining <= 0 then return nil, "timeout" end
      sock:settimeout(remaining)
    else
      sock:settimeout(idle_timeout)
    end
    return sock:receive(pattern)
  end

  local head, herr = http.read_head(read)
  if not head then
    sock:close()
    return nil, herr
  end
  in_head = false
  if head.status ~= 200 or not http.is_event_stream(head) then
    -- read a small error body (if framed) for the message, then give up
    local body
    if head.headers["content-length"] then
      in_head = true
      head_deadline = gettime() + self.timeout
      body = http.read_all(http.body_reader(head, read), 4096)
    end
    sock:close()
    head.json = self:decode(body)
    if head.status == 200 then
      return nil, "events: unexpected content-type " .. tostring(head.headers["content-type"]), head.status
    end
    return http_error(head, "events")
  end

  local stream = setmetatable({
    sock = sock,
    head = head,
    parser = sse.Parser.new(),
    reader = http.body_reader(head, read, { line_mode = true }),
    queue = {},
    closed = false,
  }, Stream)
  return stream
end

Client.Stream = Stream
return Client
