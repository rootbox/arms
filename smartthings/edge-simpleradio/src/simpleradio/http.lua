-- Minimal HTTP/1.1 request formatting and response parsing (pure Lua).
--
-- No sockets here: responses are read through a `read(pattern)` function with
-- LuaSocket `receive` semantics ("*l" = one line without CR/LF, number = exact
-- byte count; on failure returns nil, err, partial). Works with LuaSocket and
-- cosock sockets alike: `function(p) return sock:receive(p) end`.
--
-- Supports Content-Length bodies, chunked transfer encoding and
-- read-until-close bodies (needed for an SSE stream without framing).

local M = {}

M.USER_AGENT = "smartthings-edge-simpleradio/1"

local function header_line(name, value)
  -- prevent header injection
  value = tostring(value):gsub("[\r\n]", " ")
  return name .. ": " .. value .. "\r\n"
end

--- Build a request string.
--- @param method string
--- @param path string
--- @param opts table { host = "1.2.3.4", port = 8765, headers = {name = value}, body = string|nil }
function M.build_request(method, path, opts)
  opts = opts or {}
  local parts = { string.format("%s %s HTTP/1.1\r\n", method, path) }
  local host = opts.host or "localhost"
  if opts.port and opts.port ~= 80 then host = host .. ":" .. tostring(opts.port) end
  local seen = {}
  local function add(name, value)
    if value == nil then return end
    seen[name:lower()] = true
    parts[#parts + 1] = header_line(name, value)
  end
  add("Host", host)
  -- caller-provided headers first so they can override the defaults below
  local names = {}
  for name in pairs(opts.headers or {}) do names[#names + 1] = name end
  table.sort(names)
  for _, name in ipairs(names) do add(name, opts.headers[name]) end
  if not seen["user-agent"] then add("User-Agent", M.USER_AGENT) end
  if not seen["connection"] then add("Connection", "close") end
  if opts.body ~= nil then
    if not seen["content-type"] then add("Content-Type", "application/json; charset=utf-8") end
    add("Content-Length", #opts.body)
  elseif method == "POST" or method == "PUT" then
    add("Content-Length", 0)
  end
  parts[#parts + 1] = "\r\n"
  if opts.body ~= nil then parts[#parts + 1] = opts.body end
  return table.concat(parts)
end

--- Parse "HTTP/1.1 200 OK".
--- @return number|nil status, string reason
function M.parse_status_line(line)
  if type(line) ~= "string" then return nil, "no status line" end
  local version, code, reason = line:match("^(HTTP/%d%.%d)%s+(%d%d%d)%s*(.*)$")
  if not version then return nil, "malformed status line: " .. line:sub(1, 80) end
  return tonumber(code), reason
end

--- Parse one header line into (lowercase name, trimmed value).
function M.parse_header_line(line)
  local name, value = line:match("^([^:%s]+)%s*:%s*(.-)%s*$")
  if not name then return nil end
  return name:lower(), value
end

--- Read status line + headers.
--- @return table|nil { status, reason, headers = {lowercase = value} }, string|nil err
function M.read_head(read)
  local line, err = read("*l")
  if not line then return nil, "status line: " .. tostring(err) end
  local status, reason = M.parse_status_line(line)
  if not status then return nil, reason end
  local headers = {}
  local count = 0
  while true do
    local h, herr = read("*l")
    if not h then return nil, "headers: " .. tostring(herr) end
    if h == "" then break end
    count = count + 1
    if count > 100 then return nil, "too many headers" end
    local name, value = M.parse_header_line(h)
    if name then
      if headers[name] then
        headers[name] = headers[name] .. ", " .. value
      else
        headers[name] = value
      end
    end
  end
  return { status = status, reason = reason, headers = headers }
end

local function is_chunked(headers)
  local te = headers["transfer-encoding"]
  return te ~= nil and te:lower():find("chunked", 1, true) ~= nil
end

--- Create a body reader for a response head.
--- The returned function yields the next piece of body data as a string,
--- nil at the end of the body, or nil + err on failure.
--- @param head table from read_head
--- @param read function socket receive function
--- @param opts table|nil { line_mode = true } for unframed streams: return one line (+ "\n") per call
function M.body_reader(head, read, opts)
  local headers = head.headers or {}
  local done = false

  if head.status == 204 or head.status == 304 or (head.status >= 100 and head.status < 200) then
    return function() return nil end
  end

  if is_chunked(headers) then
    local pending_trailer = false
    return function()
      if done then return nil end
      while true do
        if pending_trailer then
          local crlf, err = read("*l") -- CRLF after chunk data
          if crlf == nil then return nil, "chunk terminator: " .. tostring(err) end
          pending_trailer = false
        end
        local size_line, err = read("*l")
        if not size_line then return nil, "chunk size: " .. tostring(err) end
        local hex = size_line:match("^%s*(%x+)")
        if not hex then return nil, "malformed chunk size: " .. size_line:sub(1, 40) end
        local size = tonumber(hex, 16)
        if size == 0 then
          -- consume optional trailers up to the blank line
          repeat
            local t, terr = read("*l")
            if not t then break end -- server may close right away; that's fine
          until t == ""
          done = true
          return nil
        end
        local data, derr, partial = read(size)
        if not data then
          return nil, "chunk data: " .. tostring(derr) .. (partial and (" after " .. #partial .. " bytes") or "")
        end
        pending_trailer = true
        if #data > 0 then return data end
      end
    end
  end

  local len = tonumber(headers["content-length"] or "")
  if len then
    local remaining = len
    return function()
      if remaining <= 0 then return nil end
      local n = remaining > 8192 and 8192 or remaining
      local data, err, partial = read(n)
      if not data then
        return nil, "body: " .. tostring(err) .. (partial and (" after " .. (len - remaining + #partial) .. " bytes") or "")
      end
      remaining = remaining - #data
      return data
    end
  end

  -- No framing: body ends when the server closes the connection.
  local line_mode = opts and opts.line_mode
  return function()
    if done then return nil end
    if line_mode then
      local line, err, partial = read("*l")
      if line then return line .. "\n" end
      done = true
      if err == "closed" then
        if partial and partial ~= "" then return partial end
        return nil
      end
      return nil, err
    end
    local data, err, partial = read(8192)
    if data then return data end
    done = true
    if err == "closed" then
      if partial and partial ~= "" then return partial end
      return nil
    end
    return nil, err
  end
end

--- Read the full body through a body reader.
--- @param max_bytes number|nil safety limit (default 256 KiB)
function M.read_all(reader, max_bytes)
  max_bytes = max_bytes or 262144
  local parts, total = {}, 0
  while true do
    local piece, err = reader()
    if piece == nil then
      if err then return nil, err end
      break
    end
    total = total + #piece
    if total > max_bytes then return nil, "body too large" end
    parts[#parts + 1] = piece
  end
  return table.concat(parts)
end

--- Read a complete response (head + body).
--- @return table|nil { status, reason, headers, body }, string|nil err
function M.read_response(read, max_bytes)
  local head, err = M.read_head(read)
  if not head then return nil, err end
  local body, berr = M.read_all(M.body_reader(head, read), max_bytes)
  if body == nil then return nil, berr end
  head.body = body
  return head
end

--- True if the Content-Type header denotes an SSE stream.
function M.is_event_stream(head)
  local ct = head and head.headers and head.headers["content-type"]
  return ct ~= nil and ct:lower():find("text/event-stream", 1, true) ~= nil
end

return M
