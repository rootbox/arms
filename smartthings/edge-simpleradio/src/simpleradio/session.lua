-- Per-device background session (ST layer).
--
-- One cosock task per device runs this loop:
--   1. address : IP/port from the persisted fields, else mDNS lookup by TXT id
--   2. pairing : no token -> POST /api/v1/pair every 10 s for 5 minutes, then a
--                dormant mode that watches GET /api/v1/info.pairingOpen every 30 s
--   3. events  : GET /api/v1/events (SSE) -> state -> capability events (changes only);
--                reconnect with backoff 2/5/10/30 s; 401 -> drop token, re-pair;
--                3 connection failures in a row -> mDNS re-resolve by id;
--                3 SSE failures in a row (or 404) -> poll GET /api/v1/state every 10 s,
--                retrying SSE every 60 s.
-- A device-thread timer re-evaluates health every 15 s:
--   online <=> paired and (SSE connected or last state younger than 60 s).

local cosock = require "cosock"
local socket = require "cosock.socket"
local log = require "log"
local mdns = require "st.mdns"

local api = require "simpleradio.api"
local emitter = require "simpleradio.emitter"
local fields = require "simpleradio.fields"
local mdns_parse = require "simpleradio.mdns_parse"
local policy = require "simpleradio.policy"

local M = {}

M.PAIR_HINT = "태블릿에서 '스마트싱스 연결 허용'을 누르세요"

local sessions = {} -- device.id -> Session

-- float seconds (LuaSocket gettime); os.time() is too coarse for sub-second test timings
local function now() return socket.gettime() end

local Session = {}
Session.__index = Session

function Session:log(level, fmt, ...)
  local msg = string.format("[Simple Radio %s] " .. fmt, tostring(self.device.label or self.device.id), ...)
  local with = log[level .. "_with"]
  if with and level ~= "debug" then
    with({ hub_logs = true }, msg)
  else
    log[level](msg)
  end
end

--- Sleep up to `seconds`, waking early on stop() or kick().
function Session:wait(seconds)
  local deadline = now() + seconds
  while not self.stopped and not self.wake do
    local remaining = deadline - now()
    if remaining <= 0 then break end
    socket.sleep(math.min(1, remaining))
  end
  self.wake = false
end

function Session:paired()
  return self.device:get_field(fields.TOKEN) ~= nil
end

function Session:update_health(force)
  if self.stopped then return end
  local online = policy.is_online({
    paired = self:paired(),
    sse_connected = self.sse_connected,
    last_state_at = self.last_state_at,
    now = now(),
  })
  if force or online ~= self.reported_online then
    self.reported_online = online
    local ok, err = pcall(function()
      if online then self.device:online() else self.device:offline() end
    end)
    if not ok then self:log("warn", "online/offline failed: %s", tostring(err)) end
  end
end

--- Look the host up by its TXT id via mDNS and persist the address.
function Session:resolve()
  local ok, resp, err = pcall(mdns.discover, mdns_parse.SERVICE_TYPE, mdns_parse.DOMAIN)
  if not ok then
    err, resp = resp, nil
  end
  if not resp then
    self:log("warn", "mDNS 조회 실패: %s", tostring(err))
    return false
  end
  local host = mdns_parse.find_host(resp, self.device.device_network_id)
  if not host then
    self:log("info", "mDNS에서 태블릿을 찾지 못함 (id=%s)", tostring(self.device.device_network_id))
    return false
  end
  M.set_address(self.device, host)
  return true
end

function Session:note_failure()
  self.conn_failures = (self.conn_failures or 0) + 1
  if self.conn_failures >= policy.FAILURES_BEFORE_RESOLVE then
    self.conn_failures = 0
    self:log("info", "연결 실패 %d회 -> mDNS로 IP 재확인", policy.FAILURES_BEFORE_RESOLVE)
    self:resolve()
  end
end

function Session:on_state(state)
  if self.stopped then return end
  emitter.apply(self.device, state)
  self.last_state_at = now()
  self:update_health()
end

function Session:drop_token(reason)
  self:log("warn", "토큰이 거부됨(%s) -> 토큰 삭제 후 다시 페어링합니다", tostring(reason))
  self.device:set_field(fields.TOKEN, nil, { persist = true })
  self.pair_started_at = nil
  self.sse_connected = false
  self.mode = "sse"
  self.sse_failures = 0
  self:update_health()
end

function Session:hint()
  self.last_hint_at = now()
  self:log("warn", "페어링 대기 중: %s (10분 안에 연결 허용 창이 열려 있어야 합니다)", M.PAIR_HINT)
end

--- Obtain a token. Returns the token, or nil if stopped.
function Session:pair_flow()
  local c = api.client()
  if self.pair_started_at == nil then
    self.pair_started_at = now()
    self.dormant_logged = false
    self:hint()
  end
  while not self.stopped do
    if self.rearm then
      self.rearm = false
      self.pair_started_at = now()
      self.dormant_logged = false
      self:hint()
    end
    local ip, port, token = api.target(self.device)
    if token then return token end

    if not ip then
      if not self:resolve() then self:wait(policy.PAIR_INTERVAL_S) end
    elseif policy.pairing_active(self.pair_started_at, now()) then
      local tok, err, status, code = c:pair(ip, port, self.device.label)
      if tok then
        self.device:set_field(fields.TOKEN, tok, { persist = true })
        self.pair_started_at = nil
        self.conn_failures = 0
        self:log("info", "페어링 완료: 토큰을 저장했습니다")
        return tok
      end
      if status == nil then self:note_failure() end
      if code == "pairing_closed" then
        if now() - (self.last_hint_at or 0) >= 60 then self:hint() end
        self:log("debug", "pair: 연결 허용 창이 닫혀 있음 (403)")
      else
        self:log("warn", "pair 실패: %s", tostring(err))
      end
      self:wait(policy.PAIR_INTERVAL_S)
    else
      if not self.dormant_logged then
        self.dormant_logged = true
        self:log("warn",
          "5분 동안 페어링되지 않음 -> 대기 모드. %s 그러면 자동으로 연결됩니다 (또는 앱에서 새로고침/주변 기기 검색).",
          M.PAIR_HINT)
      end
      local info = c:info(ip, port)
      if info and info.pairingOpen == true then
        self:log("info", "태블릿의 연결 허용 창이 열림 -> 페어링 시도")
        self.pair_started_at = now()
      else
        if not info then self:note_failure() end
        self:wait(policy.DORMANT_INFO_POLL_S)
      end
    end
  end
  return nil
end

function Session:sse_step(token)
  local c = api.client()
  local ip, port = api.target(self.device)
  local stream, err, status = c:open_events(ip, port, token, policy.SSE_IDLE_TIMEOUT_S)
  if not stream then
    if status == 401 then return self:drop_token("events 401") end
    if status == nil then self:note_failure() end
    self.sse_failures = (self.sse_failures or 0) + 1
    self:log("warn", "이벤트 스트림 연결 실패(%d): %s", self.sse_failures, tostring(err))
    if status == 404 or status == 405 or self.sse_failures >= policy.SSE_FAILURES_BEFORE_POLL then
      self.mode = "poll"
      self.poll_since = now()
      self:log("info", "SSE를 유지할 수 없음 -> %s초 간격 상태 폴링으로 전환", policy.POLL_INTERVAL_S)
      return
    end
    self:update_health()
    self:wait(policy.backoff(self.sse_failures))
    return
  end

  local connected_at = now()
  self.sse_connected = true
  self.sse_failures = 0
  self.conn_failures = 0
  self:update_health()
  self:log("info", "이벤트 스트림 연결됨 (%s:%s)", tostring(ip), tostring(port))

  local end_reason
  local function should_stop() return self.stopped end
  while not self.stopped do
    local ev, serr = stream:next(should_stop)
    if not ev then
      end_reason = serr
      break
    end
    if ev.event == "state" then
      local state = c:decode(ev.data)
      if state then
        self:on_state(state)
      else
        self:log("warn", "상태 JSON 해석 실패: %s", tostring(ev.data):sub(1, 120))
      end
    end
  end
  stream:close()
  self.sse_connected = false
  if self.stopped then return end

  -- a stream that keeps dropping right after connecting backs off 5/10/30 s
  if now() - connected_at >= 30 then
    self.flaps = 0
  else
    self.flaps = (self.flaps or 0) + 1
  end
  local delay = policy.backoff(self.flaps + 1)
  self:log("info", "이벤트 스트림 종료(%s) -> %s초 후 재연결", tostring(end_reason), delay)
  self:update_health()
  self:wait(delay)
end

function Session:poll_step(token)
  local c = api.client()
  local ip, port = api.target(self.device)
  local state, err, status = c:state(ip, port, token)
  if state then
    self.conn_failures = 0
    self:on_state(state)
  elseif status == 401 then
    return self:drop_token("state 401")
  else
    if status == nil then self:note_failure() end
    self:log("warn", "상태 폴링 실패: %s", tostring(err))
    self:update_health()
  end
  if now() - (self.poll_since or 0) >= policy.SSE_RETRY_WHILE_POLLING_S then
    self.mode = "sse" -- try the event stream again
    return
  end
  self:wait(policy.POLL_INTERVAL_S)
end

function Session:step()
  local ip, _, token = api.target(self.device)
  if not ip then
    if not self:resolve() then
      self.resolve_failures = (self.resolve_failures or 0) + 1
      self:update_health()
      self:wait(policy.backoff(self.resolve_failures))
      return
    end
    self.resolve_failures = 0
  end
  if not token then
    self:update_health()
    token = self:pair_flow()
    if not token then return end
  end
  self.rearm = false
  if self.mode == "poll" then
    self:poll_step(token)
  else
    self:sse_step(token)
  end
end

function Session:run()
  self:log("info", "세션 시작")
  while not self.stopped do
    local ok, err = pcall(self.step, self)
    if not ok then
      self:log("error", "세션 오류: %s", tostring(err))
      self:wait(5)
    end
  end
  self:log("info", "세션 종료")
end

------------------------------------------------------------------------------
-- Public API
------------------------------------------------------------------------------

--- Persist a newly discovered/resolved address. Returns true if it changed.
function M.set_address(device, host)
  local changed = false
  -- 허브의 get_field는 없는 필드에 값을 아예 돌려주지 않을 수 있다(nil이 아니라 0개) → 바로
  -- tostring()에 넘기면 "bad argument #1 (value expected)". 지역 변수에 받아 쓴다(실허브 2026-10-07).
  local old_ip = device:get_field(fields.IP)
  if host.ip and host.ip ~= old_ip then
    log.info(string.format("[Simple Radio %s] 주소 %s -> %s", tostring(device.label),
      tostring(old_ip), host.ip))
    device:set_field(fields.IP, host.ip, { persist = true })
    changed = true
  end
  if host.port and host.port ~= device:get_field(fields.PORT) then
    device:set_field(fields.PORT, host.port, { persist = true })
    changed = true
  end
  if host.name and host.name ~= device:get_field(fields.HOST_NAME) then
    device:set_field(fields.HOST_NAME, host.name, { persist = true })
  end
  return changed
end

function M.get(device)
  return sessions[device.id]
end

--- Start (or wake) the background session for a device. Idempotent.
function M.start(driver, device)
  local s = sessions[device.id]
  if s and not s.stopped then
    s.device = device
    s.wake = true
    return s
  end
  s = setmetatable({
    driver = driver,
    device = device,
    stopped = false,
    wake = false,
    rearm = false,
    mode = "sse",
    sse_failures = 0,
    flaps = 0,
    conn_failures = 0,
    sse_connected = false,
  }, Session)
  sessions[device.id] = s
  if not s:paired() then s:update_health() end
  s.health_timer = device.thread:call_on_schedule(policy.HEALTH_CHECK_S, function()
    s:update_health()
  end, "simpleradio health")
  cosock.spawn(function() s:run() end, "simpleradio session " .. tostring(device.label))
  return s
end

function M.stop(device)
  local s = sessions[device.id]
  if not s then return end
  s.stopped = true
  s.wake = true
  sessions[device.id] = nil
  if s.health_timer then
    pcall(function() device.thread:cancel_timer(s.health_timer) end)
    s.health_timer = nil
  end
end

--- Wake the session; if unpaired, re-arm the 5-minute pairing window.
function M.kick(device)
  local s = sessions[device.id]
  if not s then return end
  if not s:paired() then s.rearm = true end
  s.wake = true
end

--- A request outside the session got 401.
function M.on_unauthorized(device)
  local s = sessions[device.id]
  if s then
    s:drop_token("401")
    s.wake = true
  else
    device:set_field(fields.TOKEN, nil, { persist = true })
  end
end

--- A request outside the session failed at the network level.
function M.note_failure(device)
  local s = sessions[device.id]
  if s then s:note_failure() end
end

--- A fresh full state was fetched outside the session (refresh).
function M.note_state(device)
  local s = sessions[device.id]
  if s then
    s.last_state_at = now()
    s:update_health()
  end
end

--- Discovery saw an already-known host again.
function M.rediscovered(device, host)
  local changed = M.set_address(device, host)
  local s = sessions[device.id]
  if not s then return end
  if changed then
    s.conn_failures = 0
    s.wake = true
  end
  -- a new "주변 기기 검색" re-arms an expired pairing window
  if not s:paired() and not policy.pairing_active(s.pair_started_at, now()) then
    s.rearm = true
    s.wake = true
  end
end

return M
