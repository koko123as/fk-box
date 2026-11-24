#!/usr/bin/env python3
"""Keep fkhost product code on the upstream wrapper package (stage 7).

    python3 scripts/check_upstream_imports.py

Every Go file of apps/android/fkhost that ships in fkhost.aar reaches
sing-box, sing, sing-tun, mihomo and Xray only through
apps/android/fkhost/upstream (docs/architecture/brain-upgrade.md), so an
upstream API change breaks the build in that one package. Exempt: the
upstream package itself, *_test.go, internal/fixture and e2e (test servers
and test-only code may import upstream packages directly).
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "apps/android/fkhost"
UPSTREAM = re.compile(r'"(github\.com/(?:sagernet|metacubex|xtls)/[^"]*)"')
EXEMPT_DIRS = ("upstream", "internal", "e2e", "probes")


def import_block(text):
    blocks = re.findall(r"^import \((.*?)^\)", text, re.M | re.S)
    blocks += re.findall(r'^import\s+(?:[\w.]+\s+)?("[^"]+")', text, re.M)
    return "\n".join(blocks)


def main():
    problems = []
    checked = 0
    for path in sorted(MODULE.rglob("*.go")):
        rel = path.relative_to(MODULE)
        if rel.name.endswith("_test.go") or rel.parts[0] in EXEMPT_DIRS:
            continue
        checked += 1
        for module in UPSTREAM.findall(import_block(path.read_text())):
            problems.append(f"{rel}: imports {module}")
    for problem in problems:
        print(f"::error file=apps/android/fkhost/{problem.split(':')[0]}::{problem}; "
              "use (or add to) apps/android/fkhost/upstream instead")
    if problems:
        return 1
    print(f"{checked} product Go files of fkhost import upstream modules only through ./upstream")
    return 0


if __name__ == "__main__":
    sys.exit(main())
