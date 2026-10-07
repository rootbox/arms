-- mDNS discovery (ST layer): `_simpleradio._tcp` -> LAN device per TXT id.

local socket = require "cosock.socket"
local log = require "log"
local mdns = require "st.mdns"

local mdns_parse = require "simpleradio.mdns_parse"
local session = require "simpleradio.session"

local M = {}

M.PROFILE = "simpleradio"
M.LABEL = "Simple Radio"
M.SCAN_INTERVAL_S = 1

-- dni -> host info; consumed by the `added` lifecycle handler
local pending = {}
-- dni -> true; creation requested during the current discovery run
local requested = {}

function M.take_pending(dni)
  local host = pending[dni]
  pending[dni] = nil
  return host
end

function M.create_message(host)
  return {
    type = "LAN",
    device_network_id = host.id,
    label = M.LABEL,
    profile = M.PROFILE,
    manufacturer = "Simple Radio",
    model = "Simple Radio Android host",
    vendor_provided_label = host.name or M.LABEL,
  }
end

--- One mDNS scan. Creates devices for unknown ids; refreshes the address of known ones.
function M.scan_once(driver)
  local ok, resp, err = pcall(mdns.discover, mdns_parse.SERVICE_TYPE, mdns_parse.DOMAIN)
  if not ok then
    err, resp = resp, nil
  end
  if not resp then
    log.warn("Simple Radio mDNS discovery failed: " .. tostring(err))
    return 0
  end
  local hosts, rejected = mdns_parse.hosts_from_response(resp)
  for _, reason in ipairs(rejected) do
    log.debug("Simple Radio mDNS response ignored: " .. tostring(reason))
  end

  local known = {}
  for _, device in ipairs(driver:get_devices()) do
    known[device.device_network_id] = device
  end

  local created = 0
  for _, host in ipairs(hosts) do
    local device = known[host.id]
    if device then
      session.rediscovered(device, host)
    elseif not requested[host.id] then
      log.info_with({ hub_logs = true }, string.format(
        "Simple Radio found: id=%s ip=%s:%s name=%s", host.id, host.ip, tostring(host.port), tostring(host.name)))
      pending[host.id] = host
      requested[host.id] = true
      driver:try_create_device(M.create_message(host))
      created = created + 1
    end
  end
  return created
end

--- Driver template `discovery` handler.
function M.handler(driver, _opts, should_continue)
  log.info("Simple Radio discovery started")
  requested = {}
  while should_continue() do
    M.scan_once(driver)
    if should_continue() then socket.sleep(M.SCAN_INTERVAL_S) end
  end
  log.info("Simple Radio discovery ended")
end

return M
