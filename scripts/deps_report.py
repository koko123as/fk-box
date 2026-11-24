#!/usr/bin/env python3
"""Dependency conflicts of sing-box, Xray and mihomo in the one fkhost go.mod.

Usage: deps_report.py SINGBOX_GO_MOD XRAY_GO_MOD MIHOMO_GO_MOD FKHOST_LIST [OTHER_LIST]
  *_GO_MOD     the upstream go.mod files at the fkhost pins (`go mod download -json`)
  FKHOST_LIST  `go list -m all` of apps/android/fkhost
  OTHER_LIST   optional second module graph to compare against
Writes Markdown to stdout. Informational: the fkhost CI prints it on every
run (stage 7, docs/architecture/brain-upgrade.md), so an upgrade shows which
shared modules MVS raised and which parallel forks it links. Brought over
from the stage 0 probe (probes/stage0/tools, commit a58fcf0).
"""
import re
import sys

FAMILIES = {
    "quic-go": re.compile(r"quic-go$"),
    "utls / TLS forks": re.compile(r"(^|/)(utls|reality|jls-tls|restls-client-go|tls)$"),
    "gVisor": re.compile(r"gvisor$"),
    "WireGuard": re.compile(r"wireguard"),
    "sing (core lib)": re.compile(r"^github.com/(sagernet|metacubex)/sing$"),
}

KEY = [
    "github.com/sagernet/sing", "github.com/metacubex/sing", "github.com/sagernet/sing-tun",
    "github.com/metacubex/utls", "github.com/refraction-networking/utls", "github.com/xtls/reality",
    "github.com/sagernet/quic-go", "github.com/apernet/quic-go", "github.com/metacubex/quic-go",
    "github.com/quic-go/quic-go", "github.com/quic-go/qpack",
    "github.com/sagernet/gvisor", "gvisor.dev/gvisor", "github.com/metacubex/gvisor",
    "golang.zx2c4.com/wireguard", "golang.zx2c4.com/wireguard/windows", "github.com/sagernet/wireguard-go",
    "github.com/metacubex/wireguard-go", "google.golang.org/grpc", "google.golang.org/protobuf",
    "golang.org/x/net", "golang.org/x/crypto", "golang.org/x/sys", "golang.org/x/sync", "golang.org/x/text",
    "github.com/miekg/dns", "github.com/klauspost/compress", "github.com/andybalholm/brotli",
    "github.com/pires/go-proxyproto", "lukechampine.com/blake3", "github.com/gofrs/uuid/v5",
    "github.com/metacubex/tfo-go", "github.com/sagernet/sing-quic", "github.com/metacubex/sing-quic",
]


def parse_go_mod(path):
    requires, replaces = {}, {}
    block = None
    for raw in open(path, encoding="utf-8"):
        line = raw.split("//")[0].strip()
        if not line:
            continue
        if line in ("require (", "replace (", "exclude (", "retract (", "tool ("):
            block = line.split()[0]
            continue
        if line == ")":
            block = None
            continue
        if line.startswith("require ") or line.startswith("replace "):
            block_once, line = line.split(None, 1)
        else:
            block_once = None
        kind = block_once or block
        if kind == "require":
            parts = line.split()
            if len(parts) >= 2:
                requires[parts[0]] = parts[1]
        elif kind == "replace" and "=>" in line:
            left, right = (s.strip() for s in line.split("=>", 1))
            replaces[left.split()[0]] = right
    return requires, replaces


def parse_list(path):
    selected = {}
    if not path:
        return selected
    for raw in open(path, encoding="utf-8"):
        parts = raw.split()
        if len(parts) >= 2:
            selected[parts[0]] = parts[1]
        elif len(parts) == 1:
            selected[parts[0]] = "(main)"
    return selected


def main():
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    names = ["sing-box", "Xray", "mihomo"]
    mods = [parse_go_mod(p) for p in sys.argv[1:4]]
    probe = parse_list(sys.argv[4])
    fkbox = parse_list(sys.argv[5]) if len(sys.argv) > 5 else {}
    reqs = [m[0] for m in mods]

    print("### fkhost: shared go.mod dependency report\n")
    for name, (_, rep) in zip(names, mods):
        if rep:
            for k, v in rep.items():
                print(f"- `{name}` go.mod has `replace {k} => {v}` — ignored when it is a dependency (only the main module's replaces apply).")
    print()
    header = "| module | sing-box | Xray | mihomo | fkhost selected | other selected | note |"
    print(header)
    print("|" + "---|" * 7)
    shared = sorted(m for m in set().union(*reqs) if sum(m in r for r in reqs) >= 2)
    rows = sorted(set(shared) | {k for k in KEY if any(k in r for r in reqs)})
    conflicts = 0
    for module in rows:
        versions = [r.get(module, "") for r in reqs]
        present = {v for v in versions if v}
        sel = probe.get(module, "")
        notes = []
        if len(present) > 1:
            notes.append("versions differ")
            conflicts += 1
        for name, v in zip(names, versions):
            if v and sel and v != sel:
                notes.append(f"{name} raised {v}→{sel}")
        if fkbox and sel and fkbox.get(module) and fkbox.get(module) != sel:
            notes.append("fkhost≠other")
        if not notes and module not in KEY:
            continue
        print(f"| `{module}` | {versions[0] or '—'} | {versions[1] or '—'} | {versions[2] or '—'} | {sel or '—'} | {fkbox.get(module, '—') if fkbox else 'n/a'} | {'; '.join(notes)} |")
    print(f"\n{conflicts} shared modules where the three go.mod files ask for different versions (MVS picks the highest).\n")
    print("#### Parallel forks (different module paths, all linked side by side)\n")
    every = set(probe) or set().union(*reqs)
    for family, pattern in FAMILIES.items():
        members = sorted(m for m in every if pattern.search(m))
        if members:
            print(f"- {family}: " + ", ".join(f"`{m}` {probe.get(m, '')}".strip() for m in members))
    if fkbox:
        only_probe = sorted(set(probe) - set(fkbox))
        diff = sorted(m for m in set(probe) & set(fkbox) if probe[m] != fkbox[m])
        print(f"\n#### fkhost vs the other module graph\n\n- modules with a different selected version: {len(diff)}")
        for m in diff[:80]:
            print(f"  - `{m}` fkhost {probe[m]} / other {fkbox[m]}")
        print(f"- modules only in the fkhost graph: {len(only_probe)}")


if __name__ == "__main__":
    main()
