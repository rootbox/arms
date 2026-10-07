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

--- POST /api/v1/command; optimistic update on success, keep state on failure.
function M.send_command(driver, device, body)
  local ip, port, token = api.target(device)
  if not token or not ip then
    dlog(device, "warn", "명령 %s 무시: 아직 페어링되지 않았습니다. %s", body.command, session.PAIR_HINT)
    session.kick(device)
    return false
  end
  local ok, err, status = api.client():command(ip, port, token, body, policy.COMMAND_TIMEOUT_S)
  if ok then
    dlog(device, "info", "명령 %s%s 전송 완료", body.command,
      body.value ~= nil and ("(" .. tostring(body.value) .. ")") or "")
    local patch = commands.optimistic_state(body)
    if patch then emitter.apply(device, patch, { partial = true }) end
    return true
  end
  dlog(device, "warn", "명령 %s 실패: %s", body.command, tostring(err))
  if status == 401 then
    session.on_unauthorized(device)
  elseif status == nil then
    session.note_failure(device)
  end
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
  local state, err, status = api.client():state(ip, port, token)
  if state then
    emitter.apply(device, state, { force = true })
    session.note_state(device)
  else
    dlog(device, "warn", "새로고침 실패: %s", tostring(err))
    if status == 401 then
      session.on_unauthorized(device)
    elseif status == nil then
      session.note_failure(device)
    end
  end
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
  session.stop(device)
end

return M
