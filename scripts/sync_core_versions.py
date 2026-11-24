#!/usr/bin/env python3
"""Keep apps/android/fkhost/go.mod on the pins of version.properties.

    python3 scripts/sync_core_versions.py --check   # CI: fail on any drift
    python3 scripts/sync_core_versions.py --apply   # go get the pins + go mod tidy (needs Go)

Stage 7 (docs/architecture/brain-upgrade.md): upgrading sing-box, mihomo or
Xray is a change of version.properties only. The upgrade workflow
(.github/workflows/core-upgrade.yml) runs --apply, which turns the pins into
go.mod/go.sum, and every CI run of the host checks with --check that the two
agree, so neither can move without the other.

Pins (version.properties key -> module in apps/android/fkhost/go.mod):
  CORE_VERSION         github.com/sagernet/sing-box   (v + value)
  MIHOMO_VERSION       github.com/metacubex/mihomo    (v + value)
  XRAY_MODULE_VERSION  github.com/xtls/xray-core      (as is; XRAY_VERSION is its tag)
  GOMOBILE_VERSION     github.com/sagernet/gomobile   (as is; a go.mod tool, bound by build-fkhost)
GO_VERSION must be at least the go directive of go.mod.
sing, sing-tun and the rest follow from those modules' own go.mod files (MVS).
"""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPS = ROOT / "version.properties"
MODULE_DIR = ROOT / "apps/android/fkhost"
GO_MOD = MODULE_DIR / "go.mod"

PINS = [
    ("CORE_VERSION", "github.com/sagernet/sing-box", True),
    ("MIHOMO_VERSION", "github.com/metacubex/mihomo", True),
    ("XRAY_MODULE_VERSION", "github.com/xtls/xray-core", False),
    ("GOMOBILE_VERSION", "github.com/sagernet/gomobile", False),
]
TOOLS = ["github.com/sagernet/gomobile/cmd/gomobile", "github.com/sagernet/gomobile/cmd/gobind"]


def read_props():
    props = {}
    for line in PROPS.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def wanted(props):
    pins = {}
    for key, module, prefix in PINS:
        if key not in props:
            raise SystemExit(f"version.properties has no {key}")
        value = props[key]
        pins[module] = ("v" + value.removeprefix("v")) if prefix else value
    return pins


def parse_go_mod(text):
    requires, tools, go = {}, set(), None
    block = None
    for raw in text.splitlines():
        line = raw.split("//", 1)[0].strip()
        if not line:
            continue
        if line in ("require (", "tool (", "replace (", "exclude (", "retract ("):
            block = line.split()[0]
            continue
        if line == ")":
            block = None
            continue
        kind = block
        for word in ("require", "tool", "replace", "go"):
            if line.startswith(word + " "):
                kind, line = word, line[len(word) + 1:].strip()
                break
        parts = line.split()
        if kind == "go" and parts:
            go = parts[0]
        elif kind == "require" and len(parts) >= 2:
            requires[parts[0]] = parts[1]
        elif kind == "tool" and parts:
            tools.add(parts[0])
        elif kind == "replace" and parts:
            requires.setdefault("replace:" + parts[0], line)
    return requires, tools, go


def version_tuple(text):
    return tuple(int(part) for part in re.findall(r"\d+", text)[:3])


def check(props):
    requires, tools, go = parse_go_mod(GO_MOD.read_text())
    problems = []
    for module, version in wanted(props).items():
        have = requires.get(module)
        if have != version:
            problems.append(f"{module}: go.mod has {have or 'nothing'}, version.properties pins {version}")
        if "replace:" + module in requires:
            problems.append(f"{module}: go.mod replaces it ({requires['replace:' + module]}); pins must come from the proxy")
    for tool in TOOLS:
        if tool not in tools:
            problems.append(f"go.mod has no `tool {tool}` (gomobile must come from go.mod, not a go get in CI)")
    go_version = props.get("GO_VERSION", "").removeprefix("go")
    if go and version_tuple(go) > version_tuple(go_version):
        problems.append(f"go.mod needs go {go}, GO_VERSION is go{go_version}")
    return problems


def run(*command):
    print("+", " ".join(command), flush=True)
    environment = dict(os.environ, GOFLAGS="-mod=mod", GOTOOLCHAIN="local")
    subprocess.run(command, cwd=MODULE_DIR, env=environment, check=True)


def apply(props):
    pins = wanted(props)
    # One go get for all pins, so MVS sees them together; a pin it has to
    # raise (another module needs more) shows up in the check below.
    run("go", "get", *[f"{module}@{version}" for module, version in pins.items()])
    run("go", "get", "-tool", *[f"{tool}@{pins['github.com/sagernet/gomobile']}" for tool in TOOLS])
    run("go", "mod", "tidy")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--apply", action="store_true")
    arguments = parser.parse_args()
    props = read_props()
    if arguments.apply:
        apply(props)
    problems = check(props)
    if problems:
        for problem in problems:
            print(f"::error file=apps/android/fkhost/go.mod::{problem}")
        print("version.properties and apps/android/fkhost/go.mod disagree. Upgrades go through "
              "version.properties: run the 'Core upgrade' workflow (or scripts/sync_core_versions.py --apply).")
        return 1
    print("version.properties and apps/android/fkhost/go.mod agree: "
          + ", ".join(f"{module}@{version}" for module, version in wanted(props).items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
