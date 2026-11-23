#!/usr/bin/env python3
"""Show that a kernel shim builds the kernel with the kernel's own module set.

Run in a shim directory (singbox/, mihomo/, xray/) after `go mod tidy`:
  python3 ../check_deps.py <kernel module path>

Compares every module the shim's build list selects with the versions the
kernel's own released go.mod requires, lists the shim's replace directives
and the modules the kernel's go.mod does not name. Exit 1 if a module the
kernel requires was raised or lowered.
"""
import json
import subprocess
import sys


def go(*args):
    return subprocess.run(["go", *args], check=True, capture_output=True, text=True).stdout


def modules(text):
    decoder, i, out = json.JSONDecoder(), 0, []
    while i < len(text):
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text):
            break
        obj, i = decoder.raw_decode(text, i)
        out.append(obj)
    return out


def main():
    kernel = sys.argv[1]
    build = {m["Path"]: m for m in modules(go("list", "-m", "-json", "all"))}
    kernel_version = build[kernel]["Version"]
    gomod = json.loads(go("mod", "download", "-json", f"{kernel}@{kernel_version}"))["GoMod"]
    required = json.loads(go("mod", "edit", "-json", gomod))
    want = {r["Path"]: r["Version"] for r in required.get("Require") or []}
    kernel_replace = {r["Old"]["Path"]: r["New"] for r in required.get("Replace") or []}
    shim = json.loads(go("mod", "edit", "-json"))
    print(f"kernel {kernel} {kernel_version}: {len(want)} requirements in its go.mod")
    for r in shim.get("Replace") or []:
        same = kernel_replace.get(r["Old"]["Path"]) == r["New"]
        print(f"replace {r['Old']['Path']} => {r['New']['Path']} {r['New'].get('Version', '')}"
              f" ({'copied from the kernel go.mod' if same else 'NOT in the kernel go.mod'})")
        if not same:
            return 1
    changed = []
    for path, version in sorted(want.items()):
        got = build.get(path, {}).get("Version")
        if got is None:
            continue  # pruned: not needed by any package in the build
        if got != version:
            changed.append(f"  {path}: kernel {version} -> build {got}")
    extra = sorted(p for p, m in build.items() if p not in want and not m.get("Main") and p != kernel)
    print(f"modules in the build list: {len(build) - 1}; kernel requirements changed: {len(changed)}")
    for line in changed:
        print(line)
    print(f"modules the kernel go.mod does not name (resolved by MVS from its dependencies): {len(extra)}")
    for path in extra:
        print(f"  {path} {build[path].get('Version')}")
    return 1 if changed else 0


if __name__ == "__main__":
    sys.exit(main())
