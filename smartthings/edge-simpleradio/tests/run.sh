#!/usr/bin/env bash
# Runs every test for the Simple Radio Edge driver.
#
#   tests/run.sh            unit tests + mock-host integration + (if lua_libs present) driver e2e
#   tests/run.sh --fetch    also download the official SmartThings lua_libs release first
#
# Needs Lua 5.3 (the Edge hub runtime). Looked up as $LUA, lua5.3, ~/.local/lua53/bin/lua,
# /opt/homebrew/opt/lua@5.3/bin/lua. Integration tests need python3 and these 5.3 rocks:
#   luarocks --lua-version 5.3 install luasocket dkjson
#   luarocks --lua-version 5.3 install luasec OPENSSL_DIR=$(brew --prefix openssl@3)   # cosock dep
#   luarocks --lua-version 5.3 install cosock
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
LIBS_DIR="${ST_LUA_LIBS:-$HERE/.lua_libs}"

find_lua() {
  for c in "${LUA:-}" lua5.3 "$HOME/.local/lua53/bin/lua" /opt/homebrew/opt/lua@5.3/bin/lua /usr/local/opt/lua@5.3/bin/lua; do
    [ -z "$c" ] && continue
    if command -v "$c" >/dev/null 2>&1 && "$c" -v 2>&1 | grep -q "Lua 5\.3"; then echo "$c"; return; fi
  done
}
LUA_BIN="$(find_lua)"
if [ -z "$LUA_BIN" ]; then echo "Lua 5.3 not found (set LUA=...)" >&2; exit 2; fi
LUA_DIR="$(cd "$(dirname "$(command -v "$LUA_BIN")")/.." && pwd)"
LUAROCKS="$LUA_DIR/bin/luarocks"
echo "Using $("$LUA_BIN" -v 2>&1)"

if [ "${1:-}" = "--fetch" ]; then
  "$HERE/fetch_lua_libs.sh" "$LIBS_DIR" || exit 1
fi

BASE_PATH="$ROOT/src/?.lua;$ROOT/src/?/init.lua;$HERE/lib/?.lua"
ROCKS_PATH=""; ROCKS_CPATH=""
if [ -x "$LUAROCKS" ]; then
  ROCKS_PATH="$("$LUAROCKS" path --lr-path 2>/dev/null)"
  ROCKS_CPATH="$("$LUAROCKS" path --lr-cpath 2>/dev/null)"
fi
export LUA_PATH="$BASE_PATH;${ROCKS_PATH};;"
export LUA_CPATH="${ROCKS_CPATH};;"

fail=0
section() { printf '\n== %s\n' "$1"; }

section "syntax (luac -p)"
LUAC="$LUA_DIR/bin/luac"; command -v luac5.3 >/dev/null 2>&1 && LUAC=luac5.3
while IFS= read -r f; do
  "$LUAC" -p "$f" || { echo "syntax error: $f"; fail=1; }
done < <(find "$ROOT/src" "$HERE" -name '*.lua' -not -path '*/.lua_libs/*')
echo "checked $(find "$ROOT/src" "$HERE" -name '*.lua' -not -path '*/.lua_libs/*' | wc -l | tr -d ' ') files"

section "unit tests"
for t in "$HERE"/unit/test_*.lua; do
  "$LUA_BIN" "$t" || fail=1
done

# run_with_mock <lua test file> <LUA_PATH>: fresh mock host per test file
run_with_mock() {
  local port_file mock_pid rc=0
  port_file="$(mktemp)"
  python3 "$HERE/mock_host.py" --port 0 --port-file "$port_file" --quiet &
  mock_pid=$!
  for _ in $(seq 1 50); do [ -s "$port_file" ] && break; sleep 0.1; done
  if [ -s "$port_file" ]; then
    MOCK_PORT="$(cat "$port_file")" LUA_PATH="$2" "$LUA_BIN" "$1" || rc=1
  else
    echo "mock host did not start"; rc=1
  fi
  kill "$mock_pid" 2>/dev/null; wait "$mock_pid" 2>/dev/null; rm -f "$port_file"
  return $rc
}

HAVE_LUASOCKET=0
"$LUA_BIN" -e 'require "socket.core"; require "dkjson"' 2>/dev/null && HAVE_LUASOCKET=1
HAVE_LUA_LIBS=0
[ -f "$LIBS_DIR/integration_test/init.lua" ] && HAVE_LUA_LIBS=1

section "integration: HTTP client vs tests/mock_host.py (LuaSocket)"
if [ $HAVE_LUASOCKET -eq 1 ]; then
  run_with_mock "$HERE/integration/test_client_mock.lua" "$LUA_PATH" || fail=1
else
  echo "SKIPPED: LuaSocket/dkjson for Lua 5.3 not installed"
fi

section "integration: background session (cosock + LuaSocket) vs mock host"
HAVE_COSOCK=0
LUA_PATH="${ROCKS_PATH};;" "$LUA_BIN" -e 'require "cosock"' 2>/dev/null && HAVE_COSOCK=1
if [ $HAVE_LUASOCKET -eq 1 ] && [ $HAVE_LUA_LIBS -eq 1 ] && [ $HAVE_COSOCK -eq 1 ]; then
  # rocks first: native LuaSocket + upstream cosock rock; lua_libs only supplies `log`
  run_with_mock "$HERE/integration/test_session_mock.lua" \
    "${ROCKS_PATH};$LIBS_DIR/?.lua;$LIBS_DIR/?/init.lua;$BASE_PATH;;" || fail=1
else
  echo "SKIPPED: needs LuaSocket, dkjson, cosock rocks and lua_libs (tests/run.sh --fetch)"
fi

section "driver e2e (official integration_test framework)"
if [ $HAVE_LUA_LIBS -eq 1 ]; then
  E2E_LOG="$(mktemp)"
  ( cd "$ROOT/src" && LUA_PATH="$LIBS_DIR/?.lua;$LIBS_DIR/?/init.lua;./?.lua;./?/init.lua;$HERE/lib/?.lua;${ROCKS_PATH};;" \
      "$LUA_BIN" "$HERE/integration/test_driver_e2e.lua" ) > "$E2E_LOG" 2>&1
  grep -E "^(Running test|PASSED|FAILED|Passed [0-9]+ of|Failed with|Received unexpected)" "$E2E_LOG"
  if grep -qE "^Passed ([0-9]+) of \1 tests" "$E2E_LOG"; then rm -f "$E2E_LOG"; else echo "e2e FAILED (full log: $E2E_LOG)"; fail=1; fi
else
  echo "SKIPPED: lua_libs not found in $LIBS_DIR (run tests/run.sh --fetch or set ST_LUA_LIBS)"
fi

echo
if [ $fail -eq 0 ]; then echo "ALL TESTS PASSED"; else echo "SOME TESTS FAILED"; fi
exit $fail
