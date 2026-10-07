local t = require "t"
local mp = require "simpleradio.mdns_parse"

local function bytes(s)
  local out = {}
  for i = 1, #s do out[i] = s:byte(i) end
  return out
end

local ID = "0b6f1c2e-1111-4a4a-9c9c-123456789abc"

t.test("found[] with TXT as byte strings (documented shape)", function()
  local resp = { found = { {
    service_info = { name = "Simple Radio SM-T220", service_type = "_simpleradio._tcp", domain = "local" },
    host_info = { name = "android-1.local", address = "192.168.0.23", port = 8765 },
    txt = { text = { "id=" .. ID, "v=1", "port=8765", "name=거실 태블릿" } },
  } } }
  local hosts = mp.hosts_from_response(resp)
  t.eq(#hosts, 1)
  t.eq(hosts[1], { id = ID, ip = "192.168.0.23", port = 8765, name = "거실 태블릿", instance = "Simple Radio SM-T220" })
end)

t.test("found[] with TXT as byte arrays (shape used by official drivers)", function()
  local resp = { found = { {
    service_info = { name = "Simple Radio X", service_type = "_simpleradio._tcp", domain = "local" },
    host_info = { address = "10.0.0.5", port = 8765 },
    txt = { text = { bytes("id=" .. ID), bytes("v=1"), bytes("name=Simple Radio X") } },
  } } }
  local h = mp.find_host(resp, ID)
  t.eq(h.ip, "10.0.0.5")
  t.eq(h.name, "Simple Radio X")
end)

t.test("other services, IPv6, missing id and other API versions are ignored", function()
  local resp = { found = {
    { service_info = { service_type = "_hue._tcp" }, host_info = { address = "1.2.3.4" }, txt = { text = { "id=a" } } },
    { service_info = { service_type = "_simpleradio._tcp" }, host_info = { address = "fe80::1" }, txt = { text = { "id=b" } } },
    { service_info = { service_type = "_simpleradio._tcp" }, host_info = { address = "1.2.3.5" }, txt = { text = { "v=1" } } },
    { service_info = { service_type = "_simpleradio._tcp" }, host_info = { address = "1.2.3.6" }, txt = { text = { "id=c", "v=2" } } },
  } }
  local hosts, rejected = mp.hosts_from_response(resp)
  t.eq(#hosts, 0)
  t.eq(#rejected, 3)
end)

t.test("duplicate answers (IPv4 + IPv6 per interface) deduplicate by id", function()
  local entry = function(ip) return {
    service_info = { service_type = "_simpleradio._tcp" }, host_info = { address = ip, port = 8765 },
    txt = { text = { "id=" .. ID } } } end
  local hosts = mp.hosts_from_response({ found = { entry("192.168.0.2"), entry("fe80::2"), entry("192.168.0.2") } })
  t.eq(#hosts, 1)
end)

t.test("port falls back to TXT port, then 8765", function()
  local h = mp.find_host({ found = { { service_info = { service_type = "_simpleradio._tcp" },
    host_info = { address = "1.1.1.1" }, txt = { text = { "id=x", "port=9000" } } } } }, "x")
  t.eq(h.port, 9000)
  local h2 = mp.find_host({ found = { { service_info = { service_type = "_simpleradio._tcp" },
    host_info = { address = "1.1.1.1" }, txt = { text = { "id=y" } } } } }, "y")
  t.eq(h2.port, 8765)
end)

t.test("raw answers/additional records fallback (jbl-style)", function()
  local resp = {
    found = {},
    answers = {
      { name = "Simple Radio T._simpleradio._tcp.local", kind = { SrvRecord = { target = "tab.local", port = 8765 } } },
      { name = "Simple Radio T._simpleradio._tcp.local", kind = { TxtRecord = { text = { "id=" .. ID, "v=1" } } } },
    },
    additional = { { name = "tab.local", kind = { ARecord = { ipv4 = "192.168.1.50" } } } },
  }
  local h = mp.find_host(resp, ID)
  t.ok(h, "host found from records")
  t.eq(h.ip, "192.168.1.50")
  t.eq(h.port, 8765)
end)

t.test("nil / garbage responses", function()
  t.eq(#mp.hosts_from_response(nil), 0)
  t.eq(#mp.hosts_from_response({}), 0)
  t.is_nil(mp.find_host({ found = { { } } }, ID))
end)

t.test("is_ipv4", function()
  t.ok(mp.is_ipv4("192.168.0.1"))
  t.ok(not mp.is_ipv4("256.1.1.1"))
  t.ok(not mp.is_ipv4("fe80::1"))
end)

t.run("test_mdns_parse")
