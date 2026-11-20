#!/usr/bin/env python3
"""Command service guard, run after setup-core (needs .core/).

fk-box serves sing-box's StartedService gRPC service itself
(core/core_command_server.go): coreStartedService embeds
daemon.UnimplementedStartedServiceServer, so a method it does not implement
answers "unimplemented" to the app and to remote controllers. Every method of
the StartedServiceServer interface must therefore be implemented by
coreStartedService, answering from Xray or mihomo while one of them runs and
from sing-box's attached service otherwise. A sing-box upgrade that adds a
method fails here until it is handled.

It also fails when the Android app calls a libbox entry point that acts on
libbox's own StartedService (CommandServer.start, close, needWIFIState,
pause, wake, resetNetwork, ...) or parses a profile without dropping the
retired capture options (Libbox.hasTunInbound, formatConfig, checkConfig in
main sources) instead of fk-box's replacement.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRPC = ROOT / ".core/daemon/started_service_grpc.pb.go"

source = GRPC.read_text()
block = re.search(r"type StartedServiceServer interface \{(.*?)\n\}", source, re.S)
if not block:
    sys.exit("StartedServiceServer interface not found in " + str(GRPC))
methods = re.findall(r"^\s+([A-Z]\w*)\(", block.group(1), re.M)
handled = set()
for path in (ROOT / "core").glob("*.go"):
    if not path.name.endswith("_test.go"):
        handled |= set(re.findall(r"func \(s \*coreStartedService\) (\w+)\(", path.read_text()))

missing = [m for m in methods if m not in handled]
for method in missing:
    print(f"::error::StartedService.{method} is not implemented by coreStartedService (core/core_api.go, core_extra.go, core_command_server.go)")
print(f"{len(methods)} StartedService methods: {len(handled & set(methods))} implemented by coreStartedService")

# The app must not call the libbox entry points that act on libbox's own
# StartedService, which no longer runs sing-box: each has a fk-box
# replacement on CommandServer (core_command_server.go, core_singbox_host.go)
# or in Libbox (core_service_options.go, core_profile.go).
LIBBOX_SERVICE_CALLS = re.compile(
    r"\b\w*[Ss]erver\)?\??\.(start|close|startOrReloadService|closeService|needWIFIState|needFindProcess|"
    r"pause|wake|wakeNow|resetNetwork|updateWIFIState|setError|writeMessage)\(")
LIBBOX_PARSE_CALLS = re.compile(r"\bLibbox\.(hasTunInbound|formatConfig|checkConfig)\(")
stale = []
main_sources = ROOT / "clients/android/app/src/main"
for path in sorted((ROOT / "clients/android/app/src").rglob("*.kt")):
    text = path.read_text()
    if "io.nekohasekai.libbox.CommandServer" in text:
        for match in LIBBOX_SERVICE_CALLS.finditer(text):
            stale.append((path, text.count("\n", 0, match.start()) + 1, f"CommandServer.{match.group(1)}"))
    if main_sources in path.parents:
        for match in LIBBOX_PARSE_CALLS.finditer(text):
            stale.append((path, text.count("\n", 0, match.start()) + 1, f"Libbox.{match.group(1)}"))
for path, line, call in stale:
    print(f"::error file={path.relative_to(ROOT)},line={line}::{call} reads libbox's own service or parser; use fk-box's Core variant")
sys.exit(1 if missing or stale else 0)
