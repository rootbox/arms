-- SmartThings Edge LAN driver for the "Simple Radio" Android host (wall tablet).
-- Contract: smartthings/LAN_API.md (HTTP/1.1 + SSE, mDNS `_simpleradio._tcp`).

local Driver = require "st.driver"

local discovery = require "simpleradio.discovery"
local handlers = require "simpleradio.handlers"

local driver = Driver("simpleradio-lan", {
  discovery = discovery.handler,
  lifecycle_handlers = {
    added = handlers.device_added,
    init = handlers.device_init,
    removed = handlers.device_removed,
  },
  capability_handlers = handlers.capability_handlers(),
})

driver:run()
