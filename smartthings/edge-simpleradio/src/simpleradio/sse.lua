-- Server-Sent Events parser (pure Lua, no SmartThings dependencies).
--
-- Follows the WHATWG "event stream interpretation" rules closely enough for
-- the Simple Radio host:
--   * lines end with LF, CRLF or CR;
--   * a blank line dispatches the buffered event;
--   * lines starting with ":" are comments (the host sends ": ping");
--   * "field: value" -> a single leading space of the value is stripped;
--   * "data" lines are joined with "\n";
--   * an event with an empty data buffer is not dispatched.
--
-- Usage:
--   local parser = sse.Parser.new()
--   for _, ev in ipairs(parser:feed(chunk)) do
--     -- ev.event (defaults to "message"), ev.data, ev.id
--   end

local sse = {}

local Parser = {}
Parser.__index = Parser

function Parser.new()
  local self = setmetatable({}, Parser)
  self:reset()
  return self
end

--- Clear all buffered state (call after a reconnect).
function Parser:reset()
  self._buf = ""          -- incomplete line carried between feeds
  self._pending_cr = false -- last feed ended in CR; a following LF belongs to it
  self._event = nil
  self._data = nil        -- list of data lines
  self._id = nil
  self.retry_ms = nil
end

local function process_field(self, field, value)
  if field == "event" then
    self._event = value
  elseif field == "data" then
    self._data = self._data or {}
    self._data[#self._data + 1] = value
  elseif field == "id" then
    if not value:find("\0", 1, true) then
      self._id = value
    end
  elseif field == "retry" then
    if value:match("^%d+$") then
      self.retry_ms = tonumber(value)
    end
  end
  -- unknown fields are ignored per spec
end

local function dispatch(self, out)
  if self._id ~= nil then
    self.last_event_id = self._id
  end
  if self._data ~= nil then
    out[#out + 1] = {
      event = (self._event ~= nil and self._event ~= "") and self._event or "message",
      data = table.concat(self._data, "\n"),
      id = self.last_event_id,
    }
  end
  self._event, self._data, self._id = nil, nil, nil
end

--- Feed exactly one line (without its terminator).
--- @return table|nil the dispatched event if this line was blank and an event was pending
function Parser:feed_line(line, out)
  out = out or {}
  local before = #out
  if line == "" then
    dispatch(self, out)
  elseif line:sub(1, 1) == ":" then
    -- comment / keep-alive
    self.last_comment = line:sub(2)
  else
    local colon = line:find(":", 1, true)
    local field, value
    if colon then
      field = line:sub(1, colon - 1)
      value = line:sub(colon + 1)
      if value:sub(1, 1) == " " then value = value:sub(2) end
    else
      field, value = line, ""
    end
    process_field(self, field, value)
  end
  if #out > before then return out[#out] end
  return nil
end

--- Feed an arbitrary chunk of bytes. Returns a (possibly empty) list of events.
function Parser:feed(chunk)
  local out = {}
  if chunk == nil or chunk == "" then return out end
  if self._pending_cr and chunk:sub(1, 1) == "\n" then
    chunk = chunk:sub(2)
  end
  self._pending_cr = false
  local buf = self._buf .. chunk
  local pos = 1
  while true do
    local s, e = buf:find("[\r\n]", pos)
    if not s then break end
    local line = buf:sub(pos, s - 1)
    local term = buf:sub(s, s)
    local next_pos = e + 1
    if term == "\r" then
      if s == #buf then
        -- CR at the very end: the LF (if any) arrives in the next chunk
        self._pending_cr = true
      elseif buf:sub(s + 1, s + 1) == "\n" then
        next_pos = s + 2
      end
    end
    self:feed_line(line, out)
    pos = next_pos
  end
  self._buf = buf:sub(pos)
  return out
end

--- True if a partially received event is buffered (useful for diagnostics).
function Parser:has_pending()
  return self._buf ~= "" or self._data ~= nil or self._event ~= nil
end

sse.Parser = Parser
return sse
