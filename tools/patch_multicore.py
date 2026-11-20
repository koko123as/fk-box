#!/usr/bin/env python3
"""Serve the multi-core gRPC wrapper (core/core_api.go) instead of the bare
sing-box StartedService, so the UI and remote control read Xray and mihomo
through the same interface.

    python3 tools/patch_multicore.py .core            # apply
    python3 tools/patch_multicore.py .core --check    # only report whether it fits
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from patchcheck import check_anchor, report  # noqa: E402

LABEL = "patch_multicore"
FILE = "experimental/libbox/command_server.go"
OLD = "daemon.RegisterStartedServiceServer(s.grpcServer, s.StartedService)"
NEW = "daemon.RegisterStartedServiceServer(s.grpcServer, &coreStartedService{s.StartedService})"


def main(argv):
    check = "--check" in argv
    arguments = [a for a in argv if a != "--check"]
    if len(arguments) != 1:
        print(__doc__)
        return 2
    path = Path(arguments[0]) / FILE
    if not path.is_file():
        return report([f"{LABEL}: {FILE}: file not found in the core"], LABEL, "")
    text = path.read_text()
    if check:
        return report(check_anchor(text, OLD, FILE, LABEL), LABEL, f"{FILE}: StartedService registration found once")
    if NEW not in text:
        if text.count(OLD) != 1:
            sys.exit("patch_multicore: StartedService registration not found exactly once")
        path.write_text(text.replace(OLD, NEW))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
