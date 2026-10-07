-- LuaSocket-like receive() over an in-memory string, optionally delivered in
-- several "packets" to exercise boundary handling.
--   local read = fake_reader.new("HTTP/1.1 200 OK\r\n...")
--   read("*l") / read(n)  -> data | nil, "closed", partial
local M = {}

function M.new(data, opts)
  local pos = 1
  local closed_err = (opts and opts.err) or "closed"
  return function(pattern)
    if pattern == "*l" then
      if pos > #data then return nil, closed_err, "" end
      local s = data:find("\n", pos, true)
      if not s then
        local partial = data:sub(pos)
        pos = #data + 1
        return nil, closed_err, partial
      end
      local line = data:sub(pos, s - 1):gsub("\r", "")
      pos = s + 1
      return line
    elseif type(pattern) == "number" then
      if pos > #data then return nil, closed_err, "" end
      local avail = #data - pos + 1
      if avail < pattern then
        local partial = data:sub(pos)
        pos = #data + 1
        return nil, closed_err, partial
      end
      local out = data:sub(pos, pos + pattern - 1)
      pos = pos + pattern
      return out
    end
    error("unsupported pattern " .. tostring(pattern))
  end
end

return M
