#!/usr/bin/env bash
# Runs on the emulator host: installs the probe, lets it open a VPN without
# the consent dialog (appops), and for each kernel checks that a TCP connection
# the shell user makes into the VPN route shows up in the kernel's own log,
# i.e. the kernel read the packet from the TUN it got as fd 3.
set -uo pipefail
apk=$1; logs=$2
mkdir -p "$logs"
pkg=io.fkbox.kernelprobe
adb install -r -g "$apk"
adb shell appops set "$pkg" ACTIVATE_VPN allow
failed=0
probe() { # kernel stack expect
  local name="$1-$2"
  adb shell am force-stop "$pkg"
  adb logcat -c
  adb shell am start -W -n "$pkg/.MainActivity" --es kernel "$1" --es stack "$2" >/dev/null
  sleep 12
  for i in 1 2 3; do adb shell "toybox nc -w 2 10.233.0.5 80 </dev/null" >/dev/null 2>&1; sleep 1; done
  sleep 16
  adb logcat -d -s KPROBE:I > "$logs/$name.txt"
  echo "== $name"; cat "$logs/$name.txt"
  local ok=1
  grep -q 'EXEC libxray.so exit=0' "$logs/$name.txt" || { echo "FAIL $name: libxray.so did not run from nativeLibraryDir"; ok=0; }
  grep -q 'EXEC libmihomo.so exit=0' "$logs/$name.txt" || { echo "FAIL $name: libmihomo.so did not run from nativeLibraryDir"; ok=0; }
  grep -q 'PROBE descriptor sent' "$logs/$name.txt" || { echo "FAIL $name: TUN descriptor not handed over"; ok=0; }
  grep -q 'FDEXEC kernel exited early' "$logs/$name.txt" && { echo "FAIL $name: kernel exited early"; ok=0; }
  grep -E 'KERNEL .*10\.233\.0\.5:80' "$logs/$name.txt" >/dev/null || { echo "FAIL $name: no connection to 10.233.0.5:80 in the kernel log"; ok=0; }
  if [[ $ok == 1 ]]; then echo "PASS $name"; else failed=1; fi
}
probe xray -
probe mihomo gvisor
probe mihomo system
{
  echo "### Emulator (API 34, x86_64)"
  for f in "$logs"/*.txt; do
    n=$(basename "$f" .txt)
    if grep -E 'KERNEL .*10\.233\.0\.5:80' "$f" >/dev/null && grep -q 'PROBE descriptor sent' "$f"; then echo "- $n: kernel read the TUN on fd 3"; else echo "- $n: FAILED"; fi
  done
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit $failed
