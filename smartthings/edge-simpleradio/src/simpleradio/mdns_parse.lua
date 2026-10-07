-- Extract Simple Radio hosts from an `st.mdns.discover()` response (pure Lua).
--
-- Response shape (lua_libs st/mdns.lua, ServiceDiscoveryResponse):
--   { found = { { service_info = { name, service_type, domain },
--                 host_info = { name, address, port },
--                 txt = { text = { <record>, ... } } }, ... },
--     answers = {...}, additional = {...} }   -- raw records, seen in official drivers
-- The docs describe each TXT record as a byte string, while official drivers
-- (jbl, harman-luxury) treat them as arrays of byte values; both are accepted.
--
-- Advertised by the host (LAN_API.md "발견"):
--   service `_simpleradio._tcp`, port 8765, TXT id=<uuid> v=1 port=8765 name=<label>

local M = {}

M.SERVICE_TYPE = "_simpleradio._tcp"
M.DOMAIN = "local"
M.DEFAULT_PORT = 8765
M.API_VERSION = "1"

local function txt_to_string(rec)
  if type(rec) == "string" then return rec end
  if type(rec) == "table" then
    local ok, s = pcall(function()
      local bytes = {}
      for i, b in ipairs(rec) do bytes[i] = b end
      return string.char(table.unpack(bytes))
    end)
    if ok then return s end
  end
  return nil
end

--- Parse a list of TXT records ("key=value" items) into a table.
function M.parse_txt(records)
  local out = {}
  if type(records) == "string" then records = { records } end
  if type(records) ~= "table" then return out end
  for _, rec in ipairs(records) do
    local s = txt_to_string(rec)
    if s then
      local key, value = s:match("^([^=]+)=(.*)$")
      if key then
        out[key:lower()] = value
      elseif s ~= "" then
        out[s:lower()] = true
      end
    end
  end
  return out
end

function M.is_ipv4(ip)
  if type(ip) ~= "string" then return false end
  local a, b, c, d = ip:match("^(%d+)%.(%d+)%.(%d+)%.(%d+)$")
  if not a then return false end
  for _, o in ipairs({ a, b, c, d }) do
    if tonumber(o) > 255 then return false end
  end
  return true
end

local function service_matches(service_type)
  if type(service_type) ~= "string" then return false end
  -- tolerate a trailing domain/dot ("_simpleradio._tcp.local.")
  return service_type == M.SERVICE_TYPE or service_type:sub(1, #M.SERVICE_TYPE + 1) == M.SERVICE_TYPE .. "."
end

local function valid_port(p)
  p = tonumber(p)
  if p and p > 0 and p < 65536 then return math.tointeger(p) or p end
  return nil
end

local function make_host(txt, ip, port, instance)
  local id = txt.id
  if type(id) ~= "string" or id == "" then return nil, "missing TXT id" end
  if txt.v ~= nil and tostring(txt.v) ~= M.API_VERSION then
    return nil, "unsupported api version v=" .. tostring(txt.v)
  end
  if not M.is_ipv4(ip) then return nil, "not an IPv4 address: " .. tostring(ip) end
  return {
    id = id,
    ip = ip,
    port = valid_port(port) or valid_port(txt.port) or M.DEFAULT_PORT,
    name = (type(txt.name) == "string" and txt.name ~= "") and txt.name or instance,
    instance = instance,
  }
end

-- Fallback for responses that only carry raw DNS records (answers/additional).
local function from_records(resp, hosts, rejected)
  local records = {}
  for _, list in ipairs({ resp.answers or {}, resp.additional or {} }) do
    for _, r in ipairs(list) do records[#records + 1] = r end
  end
  local srv_by_name, txt_by_name, a_by_host = {}, {}, {}
  for _, r in ipairs(records) do
    local kind = type(r) == "table" and r.kind or nil
    if type(kind) == "table" then
      if kind.SrvRecord then srv_by_name[r.name] = kind.SrvRecord end
      if kind.TxtRecord then txt_by_name[r.name] = kind.TxtRecord.text end
      if kind.ARecord and kind.ARecord.ipv4 then a_by_host[r.name] = kind.ARecord.ipv4 end
    end
  end
  for name, srv in pairs(srv_by_name) do
    if type(name) == "string" and name:find(M.SERVICE_TYPE, 1, true) then
      local txt = M.parse_txt(txt_by_name[name])
      local ip = a_by_host[srv.target]
      local host, err = make_host(txt, ip, srv.port, name)
      if host then
        hosts[#hosts + 1] = host
      else
        rejected[#rejected + 1] = err
      end
    end
  end
end

--- @param resp table|nil mdns.discover() result
--- @return table list of { id, ip, port, name, instance } (deduplicated by id; first IPv4 wins)
--- @return table list of rejection reasons (for logging)
function M.hosts_from_response(resp)
  local hosts, rejected = {}, {}
  if type(resp) ~= "table" then return hosts, rejected end
  for _, found in ipairs(resp.found or {}) do
    local si = found.service_info or {}
    local hi = found.host_info or {}
    if service_matches(si.service_type) then
      local txt = M.parse_txt(found.txt and found.txt.text)
      local host, err = make_host(txt, hi.address, hi.port, si.name)
      if host then
        hosts[#hosts + 1] = host
      else
        rejected[#rejected + 1] = err
      end
    end
  end
  if #hosts == 0 then from_records(resp, hosts, rejected) end

  local seen, unique = {}, {}
  for _, h in ipairs(hosts) do
    if not seen[h.id] then
      seen[h.id] = true
      unique[#unique + 1] = h
    end
  end
  return unique, rejected
end

--- Find a single host by id.
function M.find_host(resp, id)
  for _, h in ipairs((M.hosts_from_response(resp))) do
    if h.id == id then return h end
  end
  return nil
end

return M
