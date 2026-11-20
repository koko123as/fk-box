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
sys.exit(1 if missing else 0)
