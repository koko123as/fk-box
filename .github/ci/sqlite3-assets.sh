#!/usr/bin/env bash
# CI only. package:sqlite3's build hook downloads its prebuilt Android library
# from GitHub releases with a single unretried request and fails the build with
# "Hash of downloaded file ... expected ..." when that request is cut short.
# This fetches the file once with retries, checks it against the hash the
# package itself pins (lib/src/hook/asset_hashes.dart), serves it on
# 127.0.0.1:8765 and points the hook there through its url_pattern user-define
# (added to flutter/pubspec.yaml in the CI checkout only).
set -euo pipefail
cd "$(dirname "$0")/../../flutter"
version=$(awk '/^  sqlite3:/{f=1} f&&/version:/{gsub(/"/,"",$2); print $2; exit}' pubspec.lock)
pkg="${PUB_CACHE:-$HOME/.pub-cache}/hosted/pub.dev/sqlite3-$version"
hashes="$pkg/lib/src/hook/asset_hashes.dart"
test -f "$hashes" || { echo "missing $hashes (run flutter pub get first)"; exit 1; }
tag="sqlite3-$version"
root="${RUNNER_TEMP:-/tmp}/sqlite3-assets"
mkdir -p "$root/$tag"
for file in libsqlite3.arm64.android.so; do
  want=$(grep -F "'$file'" "$hashes" | sed -E "s/.*: '([0-9a-f]{64})'.*/\1/")
  test -n "$want" || { echo "no pinned hash for $file"; exit 1; }
  out="$root/$tag/$file"
  for attempt in 1 2 3 4 5; do
    curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 --max-time 300 \
      -o "$out" "https://github.com/simolus3/sqlite3.dart/releases/download/$tag/$file" || true
    got=$(sha256sum "$out" 2>/dev/null | cut -d' ' -f1 || true)
    [ "$got" = "$want" ] && break
    echo "attempt $attempt: $file sha256 ${got:-none}, expected $want"; sleep $((attempt * 5))
  done
  [ "$got" = "$want" ] || { echo "could not fetch $file"; exit 1; }
  echo "$file $got"
done
nohup python3 -m http.server 8765 --bind 127.0.0.1 --directory "$root" >"$root/server.log" 2>&1 &
python3 - <<'PY'
import re
p = 'pubspec.yaml'
s = open(p).read()
block = "    sqlite3:\n      source: sqlite3\n      url_pattern: \"http://127.0.0.1:8765/$RELEASE_TAG/$FILENAME\"\n"
s, n = re.subn(r'(hooks:\n  user_defines:\n)', lambda m: m.group(1) + block, s, count=1)
assert n == 1, 'hooks.user_defines not found in pubspec.yaml'
open(p, 'w').write(s)
PY
sleep 1
curl -fsS -o /dev/null "http://127.0.0.1:8765/$tag/libsqlite3.arm64.android.so"
echo "sqlite3 $version assets served from 127.0.0.1:8765"
