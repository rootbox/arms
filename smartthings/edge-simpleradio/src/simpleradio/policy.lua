-- Timing / retry policy (pure Lua). Kept separate so it can be unit tested.

local M = {}

M.PAIR_INTERVAL_S = 10          -- POST /api/v1/pair every 10 s ...
M.PAIR_WINDOW_S = 5 * 60        -- ... for up to 5 minutes
M.DORMANT_INFO_POLL_S = 30      -- after that, check /info.pairingOpen every 30 s
M.COMMAND_TIMEOUT_S = 5         -- POST /api/v1/command (host answers within 5 s)
M.REQUEST_TIMEOUT_S = 5         -- other short requests
M.SSE_IDLE_TIMEOUT_S = 60       -- host pings every 25 s; 60 s of silence = dead stream
M.POLL_INTERVAL_S = 10          -- GET /api/v1/state fallback polling
M.SSE_RETRY_WHILE_POLLING_S = 60 -- while in polling mode, retry SSE this often
M.SSE_FAILURES_BEFORE_POLL = 3  -- consecutive SSE failures before falling back to polling
M.FAILURES_BEFORE_RESOLVE = 3   -- consecutive connection failures before mDNS re-resolve
M.HEALTH_CHECK_S = 15           -- health timer period
M.STALE_STATE_S = 60            -- online if last state is younger than this

M.BACKOFF_S = { 2, 5, 10, 30 }

--- Reconnect delay for the n-th consecutive failure (1-based); sticks at the last value.
function M.backoff(n)
  if type(n) ~= "number" or n < 1 then n = 1 end
  local s = M.BACKOFF_S
  return s[math.min(math.floor(n), #s)]
end

--- Health rule: online when paired AND (SSE connected OR last state younger than 60 s).
--- @param s table { paired = bool, sse_connected = bool, last_state_at = number|nil, now = number }
function M.is_online(s)
  if not s.paired then return false end
  if s.sse_connected then return true end
  if s.last_state_at == nil then return false end
  return (s.now - s.last_state_at) < M.STALE_STATE_S
end

--- Whether the aggressive pairing window is still open.
function M.pairing_active(started_at, now)
  return started_at ~= nil and (now - started_at) < M.PAIR_WINDOW_S
end

return M
