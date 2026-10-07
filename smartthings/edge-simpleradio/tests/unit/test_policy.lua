local t = require "t"
local p = require "simpleradio.policy"

t.test("backoff 2/5/10/30 then stays at 30", function()
  t.eq({ p.backoff(1), p.backoff(2), p.backoff(3), p.backoff(4), p.backoff(5), p.backoff(100) },
    { 2, 5, 10, 30, 30, 30 })
  t.eq(p.backoff(0), 2)
  t.eq(p.backoff(nil), 2)
end)

t.test("health: unpaired is always offline", function()
  t.eq(p.is_online({ paired = false, sse_connected = true, last_state_at = 100, now = 100 }), false)
end)

t.test("health: SSE connected -> online", function()
  t.eq(p.is_online({ paired = true, sse_connected = true, now = 1000 }), true)
end)

t.test("health: last state younger than 60 s -> online, older -> offline", function()
  t.eq(p.is_online({ paired = true, sse_connected = false, last_state_at = 1000, now = 1059 }), true)
  t.eq(p.is_online({ paired = true, sse_connected = false, last_state_at = 1000, now = 1060 }), false)
  t.eq(p.is_online({ paired = true, sse_connected = false, now = 1000 }), false)
end)

t.test("pairing window: 5 minutes", function()
  t.eq(p.pairing_active(1000, 1000 + 299), true)
  t.eq(p.pairing_active(1000, 1000 + 300), false)
  t.eq(p.pairing_active(nil, 1000), false)
  t.eq(p.PAIR_INTERVAL_S, 10)
end)

t.run("test_policy")
