local t = require "t"
local sse = require "simpleradio.sse"

t.test("single event with event + data, blank-line terminated", function()
  local p = sse.Parser.new()
  local evs = p:feed('event: state\ndata: {"power":"on"}\n\n')
  t.eq(#evs, 1)
  t.eq(evs[1].event, "state")
  t.eq(evs[1].data, '{"power":"on"}')
end)

t.test("comments (: ping) are ignored", function()
  local p = sse.Parser.new()
  t.eq(#p:feed(": ping\n\n"), 0)
  t.eq(#p:feed(":\n"), 0)
  local evs = p:feed(": ping\nevent: state\n: ping in the middle\ndata: x\n\n")
  t.eq(#evs, 1)
  t.eq(evs[1].data, "x")
end)

t.test("event is not dispatched before the blank line", function()
  local p = sse.Parser.new()
  t.eq(#p:feed("event: state\ndata: a\n"), 0)
  t.ok(p:has_pending())
  local evs = p:feed("\n")
  t.eq(#evs, 1)
  t.eq(evs[1].data, "a")
end)

t.test("multi-line data joined with LF", function()
  local p = sse.Parser.new()
  local evs = p:feed("data: line1\ndata: line2\ndata:line3\n\n")
  t.eq(evs[1].data, "line1\nline2\nline3")
  t.eq(evs[1].event, "message")
end)

t.test("CRLF and CR line endings, split across chunks", function()
  local p = sse.Parser.new()
  local all = {}
  local stream = "event: state\r\ndata: {\"v\":1}\r\n\r\nevent: state\rdata: 2\r\r"
  -- feed one byte at a time
  for i = 1, #stream do
    for _, e in ipairs(p:feed(stream:sub(i, i))) do all[#all + 1] = e end
  end
  t.eq(#all, 2)
  t.eq(all[1].data, '{"v":1}')
  t.eq(all[2].data, "2")
end)

t.test("CR at chunk end followed by LF in next chunk is one line break", function()
  local p = sse.Parser.new()
  t.eq(#p:feed("data: a\r"), 0)
  t.eq(#p:feed("\n"), 0)       -- the LF belongs to the CR, not a blank line
  local evs = p:feed("\r\n")
  t.eq(#evs, 1)
  t.eq(evs[1].data, "a")
end)

t.test("only one leading space stripped; colons in value kept", function()
  local p = sse.Parser.new()
  local evs = p:feed("data:  two spaces: and colon\n\n")
  t.eq(evs[1].data, " two spaces: and colon")
end)

t.test("empty data buffer does not dispatch; event type resets", function()
  local p = sse.Parser.new()
  t.eq(#p:feed("event: state\n\n"), 0)
  local evs = p:feed("data: x\n\n")
  t.eq(evs[1].event, "message")
end)

t.test("id, retry and unknown fields", function()
  local p = sse.Parser.new()
  local evs = p:feed("id: 7\nretry: 1500\nfoo: bar\ndata: z\n\n")
  t.eq(evs[1].id, "7")
  t.eq(p.retry_ms, 1500)
  local evs2 = p:feed("data: next\n\n")
  t.eq(evs2[1].id, "7", "last event id persists")
end)

t.test("several events in one chunk", function()
  local p = sse.Parser.new()
  local evs = p:feed("event: state\ndata: 1\n\nevent: state\ndata: 2\n\n: ping\n\nevent: state\ndata: 3\n\n")
  t.eq(#evs, 3)
  t.eq(evs[3].data, "3")
end)

t.test("UTF-8 payload passes through untouched", function()
  local p = sse.Parser.new()
  local evs = p:feed('event: state\ndata: {"title":"KBS 쿨FM - 프로그램"}\n\n')
  t.eq(evs[1].data, '{"title":"KBS 쿨FM - 프로그램"}')
end)

t.run("test_sse")
