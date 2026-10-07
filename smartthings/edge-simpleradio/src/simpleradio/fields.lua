-- Device field names (device:set_field / get_field).
return {
  IP = "ip",                -- persist: last known host IPv4 (from mDNS)
  PORT = "port",            -- persist: host API port (TXT port, default 8765)
  TOKEN = "token",          -- persist: Bearer token from POST /api/v1/pair
  HOST_NAME = "host_name",  -- persist: TXT name of the host
  SNAPSHOT = "snapshot",    -- transient: last emitted attribute values (dedupe)
}
