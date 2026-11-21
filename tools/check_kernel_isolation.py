#!/usr/bin/env python3
"""fk-core never links a kernel (docs/kernel-isolation.md).

Runs `go list -deps` over fk-box's own module core/ (host and android/arm64)
and fails when a package of sing-box, xray-core or mihomo shows up, or when
core/go.mod requires one of them. core/tools/xrayshare is its own module and
deliberately depends on libXray (and through it xray-core); it is not part of
fk-core.

It also checks that the generated StartedService code fk-core serves on
command.sock (core/internal/daemonpb) is byte for byte the file of the
sing-box tag the app ships (version.properties CORE_VERSION).
"""
import json
import os
import pathlib
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = ROOT / "core"
KERNELS = (
    "github.com/sagernet/sing-box",
    "github.com/xtls/xray-core",
    "github.com/metacubex/mihomo",
)


def deps(goos, goarch):
    env = dict(os.environ, GOOS=goos, GOARCH=goarch, CGO_ENABLED="0", GOFLAGS="-mod=mod")
    result = subprocess.run(["go", "list", "-deps", "-f", "{{.ImportPath}}", "./..."],
                            cwd=CORE, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        sys.exit(f"go list failed for {goos}/{goarch}")
    return result.stdout.split()


COPIED = {
    "core/internal/daemonpb/started_service.pb.go": "daemon/started_service.pb.go",
    "core/internal/daemonpb/started_service_grpc.pb.go": "daemon/started_service_grpc.pb.go",
}


def copied_protos():
    failures = []
    properties = dict(line.strip().split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines() if "=" in line)
    tag = "v" + properties["CORE_VERSION"]
    for local, upstream in COPIED.items():
        url = f"https://raw.githubusercontent.com/SagerNet/sing-box/{tag}/{upstream}"
        with urllib.request.urlopen(url, timeout=60) as response:
            original = response.read()
        if (ROOT / local).read_bytes() != original:
            failures.append(f"{local} differs from sing-box {tag} {upstream}")
    return failures


def main():
    failures = copied_protos()
    module = json.loads(subprocess.run(["go", "mod", "edit", "-json"], cwd=CORE,
                                       capture_output=True, text=True, check=True).stdout)
    for require in (module.get("Require") or []) + [r["New"] for r in module.get("Replace") or []]:
        for kernel in KERNELS:
            if require["Path"] == kernel or require["Path"].startswith(kernel + "/"):
                failures.append(f"core/go.mod requires {require['Path']}")
    checked = 0
    for goos, goarch in (("linux", "amd64"), ("android", "arm64")):
        for package in deps(goos, goarch):
            checked += 1
            for kernel in KERNELS:
                if package == kernel or package.startswith(kernel + "/"):
                    failures.append(f"{goos}/{goarch}: fk-core depends on {package}")
    for failure in sorted(set(failures)):
        print(f"::error::{failure}")
    if failures:
        sys.exit(1)
    print(f"core/: {checked} package dependencies checked, no kernel module")


if __name__ == "__main__":
    main()
