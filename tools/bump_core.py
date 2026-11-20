#!/usr/bin/env python3
"""Bump one core in version.properties, after checking it can build here.

    python3 tools/bump_core.py sing-box 1.15.0-alpha.14
    python3 tools/bump_core.py xray 26.8.1
    python3 tools/bump_core.py mihomo 1.19.34
    python3 tools/bump_core.py libxray v1.260801.0
    python3 tools/bump_core.py ... --dry-run     # only report
    python3 tools/bump_core.py ... --report drift.md   # also write the drift report
    python3 tools/bump_core.py ... --no-drift    # skip the drift report

Checks before writing: the tag exists, the module resolves on the Go proxy
(Xray tags are not valid module versions, so the tag's commit is turned into
the pseudo-version Go uses), and the go directive of the new go.mod is not
newer than GO_VERSION (a newer one breaks setup-core). Only version.properties
changes; build and test on a branch (see "升级内核" in libbox.md).

Drift report: the upstream files that changed between the pinned version and
the new one, limited to what fk-box touches: the packages core/ imports from
that kernel, and for sing-box also the files fk-box's patches and scripts
edit, experimental/libbox (fk-box's files are copied into it), the daemon's
gRPC service and go.mod (with the sing-tun version the Android ingress patch
was made for). It reads GitHub's compare API (GITHUB_TOKEN raises the rate
limit); a failure to read it is reported and does not stop the bump.
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPS = ROOT / "version.properties"

# kernel -> (GitHub repository, Go module path)
KERNELS = {
    "sing-box": ("SagerNet/sing-box", "github.com/sagernet/sing-box"),
    "xray": ("XTLS/Xray-core", "github.com/xtls/xray-core"),
    "mihomo": ("MetaCubeX/mihomo", "github.com/metacubex/mihomo"),
    "libxray": ("XTLS/libXray", "github.com/xtls/libxray"),
}
# sing-box files fk-box edits by text (tools/patch_*.py) or depends on as a whole.
SING_BOX_WATCHED = [
    "experimental/libbox/",
    "daemon/started_service.go",
    "daemon/started_service.proto",
    "daemon/started_service_grpc.pb.go",
    "dns/router.go",
    "cmd/internal/build_libbox/main.go",
    "go.mod",
]


def get(url):
    headers = {"User-Agent": "b-box-bump-core"}
    if "api.github.com" in url and os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode()


def read_props():
    props = {}
    for line in PROPS.read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def write_props(changes):
    lines = PROPS.read_text().splitlines()
    for index, line in enumerate(lines):
        key = line.split("=", 1)[0].strip()
        if key in changes:
            lines[index] = f"{key}={changes[key]}"
    PROPS.write_text("\n".join(lines) + "\n")


def go_tuple(version):
    return tuple(int(part) for part in re.findall(r"\d+", version)[:3])


def go_directive(mod_text):
    match = re.search(r"^go\s+(\S+)", mod_text, re.M)
    return match.group(1) if match else "0"


def proxy(module, suffix):
    return get(f"https://proxy.golang.org/{module.lower()}/@v/{suffix}")


def tag_commit(repo, tag):
    ref = json.loads(get(f"https://api.github.com/repos/{repo}/git/ref/tags/{tag}"))
    obj = ref["object"]
    if obj["type"] == "tag":  # annotated tag
        obj = json.loads(get(obj["url"]))["object"]
    return obj["sha"]


def module_commit(module, version):
    """The commit a module version was built from: a pseudo-version's suffix,
    else the Go proxy's recorded origin, else the tag itself."""
    pseudo = re.search(r"-(?:\d{14})-([0-9a-f]{12})$", version)
    if pseudo:
        return pseudo.group(1)
    try:
        info = json.loads(proxy(module, f"{version}.info"))
        commit = (info.get("Origin") or {}).get("Hash")
        if commit:
            return commit
    except Exception:  # noqa: BLE001 - fall back to the tag name
        pass
    return version


def pinned(core, props):
    """The (old, new-to-be) refs to compare for this kernel."""
    if core == "sing-box":
        return "v" + props.get("CORE_VERSION", props.get("VERSION_NAME", ""))
    if core == "xray":
        return module_commit(KERNELS["xray"][1], props["XRAY_MODULE_VERSION"])
    if core == "mihomo":
        return "v" + props["MIHOMO_VERSION"]
    return module_commit(KERNELS["libxray"][1], props["LIBXRAY_MODULE_VERSION"])


def watched_packages(module):
    """The package directories (relative to the module) core/ imports."""
    pattern = re.compile(r'"' + re.escape(module) + r'(/[^"]*)?"')
    packages = set()
    for path in (ROOT / "core").rglob("*.go"):
        for match in pattern.finditer(path.read_text()):
            packages.add((match.group(1) or "").strip("/"))
    return sorted(packages)


def patched_files():
    """sing-box files fk-box's .patch files change."""
    files = set()
    for patch in (ROOT / "core").glob("*.patch"):
        if patch.name.startswith("sing-tun"):
            continue
        files |= set(re.findall(r"^diff --git a/(\S+) ", patch.read_text(), re.M))
    return sorted(files)


def sing_tun_version(ref):
    mod = get(f"https://raw.githubusercontent.com/SagerNet/sing-box/{ref}/go.mod")
    match = re.search(r"^\s*github\.com/sagernet/sing-tun\s+(\S+)", mod, re.M)
    return match.group(1) if match else "?"


def drift_report(core, old_ref, new_ref):
    """Markdown: what changed upstream in the files fk-box depends on."""
    repo, module = KERNELS[core]
    compare = json.loads(get(f"https://api.github.com/repos/{repo}/compare/{old_ref}...{new_ref}"))
    changed = {item["filename"]: item for item in compare.get("files", [])}
    lines = [f"## Drift report: {core} {old_ref} → {new_ref}", "",
             f"{compare.get('total_commits', '?')} commits, {len(changed)} files changed upstream "
             f"([compare](https://github.com/{repo}/compare/{old_ref}...{new_ref})).", ""]
    if len(changed) >= 300:
        lines += ["**The compare API lists at most 300 files: the list below may be incomplete; check the compare page.**", ""]
    # fk-box's own overlay package inside sing-box's tree is not upstream code.
    watched = [("package", package) for package in watched_packages(module) if package != "experimental/httpdebug"]
    if core == "sing-box":
        watched += [("file", name) for name in sorted(set(patched_files() + SING_BOX_WATCHED))]
    hits = 0
    lines += ["| fk-box depends on | changed upstream |", "| --- | --- |"]
    for kind, name in watched:
        if kind == "package":
            files = [f for f in changed if f.rsplit("/", 1)[0] == name or ("/" not in f and name == "")]
            label = f"`{name or '(module root)'}` (package imported by core/)"
        elif name.endswith("/"):
            files = [f for f in changed if f.startswith(name)]
            label = f"`{name}`"
        else:
            files = [f for f in changed if f == name]
            label = f"`{name}`"
        if files:
            hits += len(files)
            detail = ", ".join(f"`{f}` ({changed[f]['status']}, +{changed[f]['additions']}/-{changed[f]['deletions']})" for f in sorted(files))
        else:
            detail = "—"
        lines.append(f"| {label} | {detail} |")
    if core == "sing-box":
        old_tun, new_tun = sing_tun_version(old_ref), sing_tun_version(new_ref)
        note = "unchanged" if old_tun == new_tun else "**changed: rebase core/sing-tun-android-ingress.patch and VERSION in tools/patch_sing_tun.py**"
        lines += ["", f"sing-tun: `{old_tun}` → `{new_tun}` ({note})."]
    lines += ["", f"{hits} changed upstream files in what fk-box depends on."]
    return "\n".join(lines) + "\n"


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    report_path = None
    if "--report" in argv:
        index = argv.index("--report")
        if index + 1 >= len(argv):
            print("--report needs a file name")
            return 2
        report_path = Path(argv[index + 1])
        argv = argv[:index] + argv[index + 2:]
    core, version = argv[0], argv[1]
    dry = "--dry-run" in argv
    props = read_props()
    old_ref = None
    if core in KERNELS and "--no-drift" not in argv:
        try:
            old_ref = pinned(core, props)
        except Exception as error:  # noqa: BLE001 - the drift report is advisory
            print(f"drift report unavailable: {error}")
    go_version = props["GO_VERSION"].removeprefix("go")
    changes = {}
    if core == "sing-box":
        version = version.removeprefix("v")
        mod = get(f"https://raw.githubusercontent.com/SagerNet/sing-box/v{version}/go.mod")
        changes["CORE_VERSION"] = version
    elif core == "xray":
        version = version.removeprefix("v")
        sha = tag_commit("XTLS/Xray-core", f"v{version}")
        info = json.loads(proxy("github.com/xtls/xray-core", f"{sha}.info"))
        mod = proxy("github.com/xtls/xray-core", f"{info['Version']}.mod")
        changes["XRAY_VERSION"] = version
        changes["XRAY_MODULE_VERSION"] = info["Version"]
    elif core == "mihomo":
        version = version.removeprefix("v")
        mod = proxy("github.com/metacubex/mihomo", f"v{version}.mod")
        changes["MIHOMO_VERSION"] = version
    elif core == "libxray":
        version = "v" + version.removeprefix("v")
        mod = proxy("github.com/xtls/libxray", f"{version}.mod")
        changes["LIBXRAY_MODULE_VERSION"] = version
    else:
        print(f"unknown core {core}; use sing-box, xray, mihomo or libxray")
        return 2
    needs = go_directive(mod)
    print(f"{core} {version}: go.mod needs go {needs}; build uses go {go_version}")
    for key, value in changes.items():
        print(f"  {key}: {props.get(key, '')} -> {value}")
    if go_tuple(needs) > go_tuple(go_version):
        print("Refusing: the new version needs a newer Go than GO_VERSION. "
              "Raise GO_VERSION on its own first (all three cores must build with it).")
        return 1
    if old_ref is not None:
        new_ref = {"sing-box": f"v{version}", "mihomo": f"v{version}"}.get(core)
        if core == "xray":
            new_ref = sha
        elif core == "libxray":
            new_ref = module_commit(KERNELS["libxray"][1], version)
        try:
            report = drift_report(core, old_ref, new_ref)
        except Exception as error:  # noqa: BLE001 - the drift report is advisory
            report = f"## Drift report: {core}\n\nUnavailable: {error}\n"
        if report_path is not None:
            report_path.write_text(report)
            print(f"drift report written to {report_path}")
        else:
            print(report)
    if core == "xray":
        print("Also check libXray: its go.mod must accept this xray-core "
              "(python3 tools/bump_core.py libxray <version> if it needs a newer one).")
    if dry:
        print("dry run: nothing written")
        return 0
    write_props(changes)
    print("version.properties updated. Next: branch, CI (build-debug-apk + newui), device checklist.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
