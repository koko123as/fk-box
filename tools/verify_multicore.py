#!/usr/bin/env python3
"""Command service guard, run after setup-core (needs .core/).

sing-box runs on the official libbox CommandServer. fk-core serves sing-box's
StartedService gRPC service for the kernels it runs itself, Xray and mihomo
(core/fk/core_service_grpc.go): startedService embeds
daemon.UnimplementedStartedServiceServer, so a method it does not implement
answers "unimplemented" to the app and remote controllers while one of them
runs. A sing-box upgrade that adds a method is reported here as a warning.

It fails when the Android app parses a profile with the official parser
(Libbox.hasTunInbound, formatConfig, checkConfig in main sources) outside
FkCoreHost, which fk-core calls after removing the options that parser
rejects, or calls an entry point only the retired libbox fork had.
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
# fk-core serves StartedService for the kernels it runs (Xray, mihomo); the
# app's dashboard and tools read the same API whichever kernel runs.
for path in (ROOT / "core" / "fk").glob("*.go"):
    if not path.name.endswith("_test.go"):
        handled |= set(re.findall(r"func \(\w* ?\*startedService\) (\w+)\(", path.read_text()))
implemented = handled & set(methods)
missing = [m for m in methods if m not in handled]
if missing:
    # Mostly sing-box endpoint tools (Tailscale, OpenConnect, OpenVPN, USB/IP),
    # which Xray and mihomo have no counterpart for.
    print("::notice::Unimplemented while Xray/mihomo run: " + ", ".join(missing))
print(f"{len(methods)} StartedService methods: {len(implemented)} implemented by fk-core's startedService")
if len(implemented) < 10:
    sys.exit("fk-core's startedService implements too few StartedService methods; was it moved?")

# sing-box runs on the official libbox CommandServer (BoxService.kt), so its
# lifecycle calls are expected there. fk-core's sing-box checks must still go
# through FkCoreHost, which answers them after fk-core removed the profile
# fields the official parser rejects (experimental.http_rewrite, the retired
# http_debug options; docs/kernel-isolation.md).
LIBBOX_PARSE_CALLS = re.compile(r"\bLibbox\.(hasTunInbound|formatConfig|checkConfig)\(")
# Entry points only the retired fork of libbox had.
LEGACY_LIBBOX = re.compile(r"\b(UICaptureSigner|startUICapture|\w+UICapture\w*|generateUICaptureCA|releaseMemory)\b")
stale = []
main_sources = ROOT / "clients/android/app/src/main"
for path in sorted((ROOT / "clients/android/app/src").rglob("*.kt")):
    text = path.read_text()
    if main_sources in path.parents and path.name != "FkCoreHost.kt":
        for match in LIBBOX_PARSE_CALLS.finditer(text):
            stale.append((path, text.count("\n", 0, match.start()) + 1, f"Libbox.{match.group(1)}", "parses with the official parser; use fk-core (FkLib) so retired options are removed first"))
    for match in LEGACY_LIBBOX.finditer(text):
        stale.append((path, text.count("\n", 0, match.start()) + 1, match.group(1), "exists only in the retired libbox fork; use FkLib"))
for path, line, call, why in stale:
    print(f"::error file={path.relative_to(ROOT)},line={line}::{call} {why}")
sys.exit(1 if stale else 0)
