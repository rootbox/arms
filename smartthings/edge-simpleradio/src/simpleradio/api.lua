-- Shared HTTP client bound to the hub transport + per-device address/token lookup.

local Client = require "simpleradio.client"
local transport = require "simpleradio.transport"
local fields = require "simpleradio.fields"
local policy = require "simpleradio.policy"
local mdns_parse = require "simpleradio.mdns_parse"

local M = {}

local client

function M.client()
  if not client then
    client = Client.new({
      socket = transport.socket,
      json = transport.json,
      timeout = policy.REQUEST_TIMEOUT_S,
    })
  end
  return client
end

--- @return string|nil ip, number port, string|nil token
function M.target(device)
  return device:get_field(fields.IP),
    device:get_field(fields.PORT) or mdns_parse.DEFAULT_PORT,
    device:get_field(fields.TOKEN)
end

return M
