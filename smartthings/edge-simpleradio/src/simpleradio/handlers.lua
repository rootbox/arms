-- Capability command + lifecycle handlers (ST layer).

local capabilities = require "st.capabilities"
local log = require "log"

local api = require "simpleradio.api"
local commands = require "simpleradio.commands"
local discovery = require "simpleradio.discovery"
local emitter = require "simpleradio.emitter"
local policy = require "simpleradio.policy"
local session = require "simpleradio.session"

local M = {}

local function dlog(device, level, fmt, ...)
  log[level](string.format("[Simple Radio %s] " .. fmt, tostring(device.label), ...))
end

-- device.id -> pending verification timer (one per device; a new command replaces it)
local verify_timers = {}

--- Host unreachable at the network level, or the host itself could not run the command
--- (503 no media session, 504 its own 5 s budget). Counted towards the mDNS re-resolve.
local function is_connection_failure(status)
  return status == nil or status == 503 or status == 504
end

local function cancel_verify(device)
  local timer = verify_timers[device.id]
  if not timer then return end
  verify_timers[device.id] = nil
  pcall(function() device.thread:cancel_timer(timer) end)
end

--- GET /api/v1/state and emit it. `force` is passed to emitter.apply (true = everything,
--- set of keys = those even if unchanged). When the read fails, the last known values are
--- re-emitted so the app leaves its pending (spinner) state with the old value.
--- @return boolean read succeeded
local function resync(device, why, force)
  local ip, port, token = api.target(device)
  local state, err, status
  if token and ip then
    state, err, status = api.client():state(ip, port, token)
  else
    err = "토큰 없음"
  end
  if state then
    local n = emitter.apply(device, state, { force = force })
    session.note_state(device)
    dlog(device, "debug", "%s: 상태 재조회 완료 (이벤트 %d건)", why, n)
    return true
  end
  dlog(device, "warn", "%s: 상태 재조회 실패(%s) -> 마지막 상태를 다시 보냅니다", why, tostring(err))
  if status == 401 then
    session.on_unauthorized(device)
  elseif is_connection_failure(status) then
    session.note_failure(device)
  end
  emitter.reemit(device)
  return false
end

--- Verification read after a 2xx command: the host answers 200 even when the player did
--- nothing, so the real state is fetched VERIFY_DELAY_S later and the attributes the command
--- targets are emitted even if unchanged (the app waits for exactly those).
local function schedule_verify(device, body)
  cancel_verify(device)
  local keys = commands.verify_keys(body)
  local timer = device.thread:call_with_delay(policy.VERIFY_DELAY_S, function()
    verify_timers[device.id] = nil
    resync(device, "명령 " .. tostring(body.command) .. " 검증", keys)
  end, "simpleradio verify " .. tostring(body.command))
  verify_timers[device.id] = timer
end

--- POST /api/v1/command.
--- Success : no optimistic switch/playback event (the host may have done nothing); only
---           setVolume/mute/unmute are shown right away. A verification GET /state follows
---           after VERIFY_DELAY_S and emits the real values.
--- Failure : (timeout, connection error, any non-2xx) the real state is re-read immediately
---           and re-emitted in full so the app's pending switch reverts; if that read fails
---           too, the last known values are re-emitted.
function M.send_command(driver, device, body)
  local ip, port, token = api.target(device)
  if not token or not ip then
    dlog(device, "warn", "명령 %s 무시: 아직 페어링되지 않았습니다. %s", body.command, session.PAIR_HINT)
    session.kick(device)
    return false
  end
  local ok, err, status = api.client():command(ip, port, token, body, policy.COMMAND_TIMEOUT_S)
  if ok then
    dlog(device, "info", "명령 %s%s 전송 완료 -> %.1f초 뒤 상태 검증", body.command,
      body.value ~= nil and ("(" .. tostring(body.value) .. ")") or "", policy.VERIFY_DELAY_S)
    local patch = commands.optimistic_state(body)
    if patch then emitter.apply(device, patch, { partial = true }) end
    schedule_verify(device, body)
    return true
  end
  dlog(device, "warn", "명령 %s 실패: %s", body.command, tostring(err))
  cancel_verify(device)
  if status == 401 then
    session.on_unauthorized(device)
    -- the token is gone: GET /state would fail the same way, just revert the pending tile
    emitter.reemit(device)
    return false
  end
  if is_connection_failure(status) then session.note_failure(device) end
  resync(device, "명령 " .. tostring(body.command) .. " 실패 후 원복", true)
  return false
end

local function make_handler(capability_id, command_name)
  return function(driver, device, cmd)
    local body, err = commands.to_request(capability_id, command_name, cmd and cmd.args)
    if not body then
      dlog(device, "warn", "%s", tostring(err))
      return
    end
    M.send_command(driver, device, body)
  end
end

--- refresh -> GET /api/v1/state and re-emit everything.
function M.refresh(driver, device)
  local ip, port, token = api.target(device)
  if not token or not ip then
    dlog(device, "info", "새로고침: 페어링 대기 중 -> 페어링 시도를 다시 시작합니다. %s", session.PAIR_HINT)
    session.kick(device)
    return
  end
  cancel_verify(device)
  resync(device, "새로고침", true)
  session.kick(device)
end

function M.capability_handlers()
  local handlers = {}
  for _, entry in ipairs(commands.supported()) do
    local cap = capabilities[entry.capability]
    local cap_id = cap.ID
    handlers[cap_id] = handlers[cap_id] or {}
    handlers[cap_id][cap.commands[entry.command].NAME] = make_handler(entry.capability, entry.command)
  end
  handlers[capabilities.refresh.ID] = {
    [capabilities.refresh.commands.refresh.NAME] = M.refresh,
  }
  return handlers
end

------------------------------------------------------------------------------
-- Lifecycle
------------------------------------------------------------------------------

function M.device_added(driver, device)
  local host = discovery.take_pending(device.device_network_id)
  if host then session.set_address(device, host) end
  dlog(device, "info", "추가됨 (dni=%s)", tostring(device.device_network_id))
  M.device_init(driver, device)
end

function M.device_init(driver, device)
  pcall(emitter.emit_static, device)
  session.start(driver, device)
end

function M.device_removed(driver, device)
  dlog(device, "info", "삭제됨")
  cancel_verify(device)
  session.stop(device)
end

return M
