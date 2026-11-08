#!/usr/bin/env python3
"""Diff report: flutter/ against FlClash v0.9.3 and prism_ui at their pinned commits.

Usage: verify_flclash_ui.py <FlClash checkout> <prism_ui checkout> [--report FILE]

Every file FK-box imported from FlClash must be byte-identical to the pinned
source unless flclash-manifest.json lists it under "adaptedFiles" with a reason.
The report lists identical, adapted and FK-box-only files; any unlisted change
fails the check. The pins come from flclash-manifest.json.
"""
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FLUTTER = os.path.join(ROOT, "flutter")

# Directories copied from FlClash as they are (relative to the FlClash root).
FLCLASH_TREES = [
    "lib",
    "arb",
    "assets/data",
    "assets/fonts",
    "assets/images",
    "plugins/code_forge",
    "plugins/dynamic_color",
    "plugins/proxy",
    "plugins/rust_api",
    "plugins/tray",
    "plugins/wifi_ssid",
    "plugins/window",
]
FLCLASH_FILES = [
    "pubspec.yaml",
    "pubspec.lock",
    "analysis_options.yaml",
    "lint_options.yaml",
    "build.yaml",
    "LICENSE",
    ".metadata",
    "core/Clash.Meta/component/ca/ca-certificates.crt",
]
# Generated or local-only files that are never compared.
IGNORED_PARTS = {".dart_tool", "build", "target", ".git"}
IGNORED_NAMES = {".flutter-plugins", ".flutter-plugins-dependencies", ".packages"}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def walk(base, rel):
    top = os.path.join(base, rel)
    if os.path.isfile(top):
        yield rel
        return
    for directory, dirs, files in os.walk(top):
        dirs[:] = sorted(d for d in dirs if d not in IGNORED_PARTS)
        for name in sorted(files):
            if name in IGNORED_NAMES:
                continue
            path = os.path.join(directory, name)
            yield os.path.relpath(path, base)


def main():
    args = sys.argv[1:]
    report_path = None
    if "--report" in args:
        index = args.index("--report")
        report_path = args[index + 1]
        del args[index:index + 2]
    if len(args) != 2:
        print(__doc__)
        return 2
    flclash, prism = args
    with open(os.path.join(ROOT, "flclash-manifest.json")) as handle:
        manifest = json.load(handle)
    adapted = manifest.get("adaptedFiles", {})
    fkbox_only = manifest.get("fkboxFiles", {})

    pairs = []  # (flutter-relative path, reference path or None)
    for rel in FLCLASH_TREES + FLCLASH_FILES:
        reference = set(walk(flclash, rel)) if os.path.exists(os.path.join(flclash, rel)) else set()
        local = set(walk(FLUTTER, rel)) if os.path.exists(os.path.join(FLUTTER, rel)) else set()
        for path in sorted(reference | local):
            pairs.append((path, os.path.join(flclash, path) if path in reference else None))
    for path in sorted(set(walk(prism, ".")) | set(walk(FLUTTER, "plugins/prism_ui"))):
        if path.startswith(".git" + os.sep) or path == ".git":
            continue
        if path.startswith("plugins/prism_ui"):
            ref_rel = os.path.relpath(path, "plugins/prism_ui")
        else:
            ref_rel, path = path, os.path.join("plugins/prism_ui", path)
        ref = os.path.join(prism, ref_rel)
        pairs.append((path, ref if os.path.exists(ref) else None))
    pairs = sorted(dict(pairs).items())

    identical, changed, missing, extra, problems = [], [], [], [], []
    for path, ref in pairs:
        local = os.path.join(FLUTTER, path)
        if ref is None:
            extra.append(path)
            if path not in fkbox_only:
                problems.append(f"FK-box-only file not listed in fkboxFiles: flutter/{path}")
        elif not os.path.exists(local):
            missing.append(path)
            if path not in adapted:
                problems.append(f"FlClash file missing without a reason in adaptedFiles: flutter/{path}")
        elif sha256(local) == sha256(ref):
            identical.append(path)
        else:
            changed.append(path)
            if path not in adapted:
                problems.append(f"Changed FlClash file not listed in adaptedFiles: flutter/{path}")

    lines = [
        "### FlClash UI source diff",
        f"- Reference: FlClash `{manifest['commit']}`, prism_ui `{manifest['uiKit']['commit']}`",
        f"- Identical to the reference: **{len(identical)}** files"
        f" (lib/: {sum(1 for p in identical if p.startswith('lib/'))})",
        f"- Adapted (listed in flclash-manifest.json): **{len(changed)}** changed, **{len(missing)}** removed",
        f"- FK-box-only: **{len(extra)}**",
    ]
    for title, items, notes in (("Adapted", changed + missing, adapted), ("FK-box-only", extra, fkbox_only)):
        if items:
            lines.append(f"\n#### {title}")
            lines.extend(f"- `flutter/{p}`: {notes.get(p, 'UNLISTED')}" for p in items)
    if problems:
        lines.append("\n#### Problems")
        lines.extend(f"- {p}" for p in problems)
    text = "\n".join(lines) + "\n"
    print(text)
    if report_path:
        with open(report_path, "w") as handle:
            handle.write(text)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
