#!/usr/bin/env bash
# Download the official SmartThings Edge lua_libs (incl. integration_test) from the latest
# SmartThingsCommunity/SmartThingsEdgeDrivers release into $1 (default tests/.lua_libs).
set -euo pipefail
DEST="${1:-$(cd "$(dirname "$0")" && pwd)/.lua_libs}"
API="https://api.github.com/repos/SmartThingsCommunity/SmartThingsEdgeDrivers/releases/latest"
URL="$(curl -fsSL "$API" | grep -o '"browser_download_url": *"[^"]*lua_libs[^"]*\.tar\.gz"' | head -1 | sed 's/.*"\(https[^"]*\)"/\1/')"
if [ -z "$URL" ]; then echo "could not find lua_libs asset in $API" >&2; exit 1; fi
echo "fetching $URL"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/lua_libs.tar.gz" "$URL"
rm -rf "$DEST"; mkdir -p "$DEST"
tar -xzf "$TMP/lua_libs.tar.gz" -C "$DEST" --strip-components=1
echo "$URL" > "$DEST/.source"
echo "lua_libs -> $DEST"
