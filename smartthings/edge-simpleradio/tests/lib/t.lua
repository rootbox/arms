-- Tiny assert-based test runner (no busted needed).
--   local t = require "t"
--   t.test("name", function() t.eq(1, 1) end)
--   t.run()   -- exits non-zero on failure

local T = { _tests = {} }

local function serialize(v, indent, seen)
  indent = indent or ""
  seen = seen or {}
  if type(v) == "string" then return string.format("%q", v) end
  if type(v) ~= "table" then return tostring(v) end
  if seen[v] then return "<cycle>" end
  seen[v] = true
  local keys = {}
  for k in pairs(v) do keys[#keys + 1] = k end
  table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
  local parts = {}
  for _, k in ipairs(keys) do
    parts[#parts + 1] = indent .. "  " .. tostring(k) .. " = " .. serialize(v[k], indent .. "  ", seen)
  end
  return "{\n" .. table.concat(parts, ",\n") .. "\n" .. indent .. "}"
end
T.serialize = serialize

local function deep_eq(a, b)
  if a == b then return true end
  if type(a) ~= "table" or type(b) ~= "table" then return false end
  for k, v in pairs(a) do if not deep_eq(v, b[k]) then return false end end
  for k in pairs(b) do if a[k] == nil then return false end end
  return true
end
T.deep_eq = deep_eq

function T.eq(actual, expected, msg)
  if not deep_eq(actual, expected) then
    error(string.format("%sexpected %s\n  got %s", msg and (msg .. ": ") or "",
      serialize(expected), serialize(actual)), 2)
  end
end

function T.ok(v, msg)
  if not v then error(msg or "expected truthy value", 2) end
  return v
end

function T.is_nil(v, msg)
  if v ~= nil then error((msg and (msg .. ": ") or "") .. "expected nil, got " .. serialize(v), 2) end
end

function T.match(s, pattern, msg)
  if type(s) ~= "string" or not s:find(pattern) then
    error(string.format("%sexpected %s to match %q", msg and (msg .. ": ") or "", serialize(s), pattern), 2)
  end
end

function T.test(name, fn)
  T._tests[#T._tests + 1] = { name = name, fn = fn }
end

function T.run(suite)
  local passed, failed = 0, 0
  for _, tc in ipairs(T._tests) do
    local ok, err = xpcall(tc.fn, debug.traceback)
    if ok then
      passed = passed + 1
      print("  ok   " .. tc.name)
    else
      failed = failed + 1
      print("  FAIL " .. tc.name .. "\n" .. tostring(err))
    end
  end
  print(string.format("%s: %d passed, %d failed", suite or arg[0], passed, failed))
  T._tests = {}
  if failed > 0 then os.exit(1) end
end

return T
