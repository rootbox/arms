-- Hub transport: cosock sockets + the hub JSON codec.
-- Isolated in its own module so tests can substitute a fake (package.preload).
return {
  socket = require "cosock.socket",
  json = require "st.json",
}
