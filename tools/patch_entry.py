#!/usr/bin/env python3
"""Install fk-box's own Go packages into the matching sing-box checkout and
bind fk-box's gomobile entry package next to libbox.

    python3 tools/patch_entry.py .core            # apply
    python3 tools/patch_entry.py .core --check    # only report whether it fits

Overlays (new directories in the sing-box tree, under its go.mod):

- core/fkhost/  -> experimental/fkhost  fk-box's host services (no libbox import)
- core/fkbox/   -> experimental/fkbox   the gomobile entry package (Java
  io.nekohasekai.fkbox.Fkbox), bound into libbox.aar next to libbox
- core/fkcore/  -> cmd/fk-core          the desktop core process

The Android build in cmd/internal/build_libbox/main.go is changed to bind
./experimental/fkbox together with ./experimental/libbox (one AAR, one .so).
setup-core runs it right after copying ui_*.go, before the first `go mod
tidy` (ui_*.go import experimental/fkhost); fkbox and fk-core only import
packages of the sing-box module, so the tidy resolves nothing new for them.
"""
import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from patchcheck import check_absent, check_anchor, report  # noqa: E402

LABEL = "patch_entry"
OVERLAYS = [
    ("core/fkhost", "experimental/fkhost"),
    ("core/fkbox", "experimental/fkbox"),
    ("core/fkcore", "cmd/fk-core"),
]
BUILD = "cmd/internal/build_libbox/main.go"
OLD = 'args = append(args, "-tags", strings.Join(config.Tags, ","))\n\targs = append(args, "./experimental/libbox")'
NEW = 'args = append(args, "-tags", strings.Join(config.Tags, ","))\n\targs = append(args, "./experimental/libbox", "./experimental/fkbox")'


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("core", type=Path)
    parser.add_argument("--check", action="store_true", help="only report whether the overlay and the bind edit fit")
    arguments = parser.parse_args()
    core = arguments.core.resolve()
    build = core / BUILD
    targets = [core / target for _, target in OVERLAYS]
    if arguments.check:
        failures = check_absent(targets, core, LABEL)
        if not build.is_file():
            failures.append(f"{LABEL}: {BUILD}: file not found in the core")
        else:
            failures += check_anchor(build.read_text(), OLD, BUILD, LABEL)
        return report(failures, LABEL, "overlay paths are free; the Android gomobile bind line found once")
    text = build.read_text()
    if NEW not in text:
        if text.count(OLD) != 1:
            sys.exit(f"{LABEL}: the Android gomobile bind line was not found exactly once in {BUILD}")
        build.write_text(text.replace(OLD, NEW))
    for source, target in OVERLAYS:
        if not (ROOT / source).is_dir():
            continue
        destination = core / target
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        for file in sorted((ROOT / source).glob("*.go")):
            shutil.copyfile(file, destination / file.name)
    print("Installed experimental/fkhost, experimental/fkbox, cmd/fk-core; Android bind adds ./experimental/fkbox")
    return 0


if __name__ == "__main__":
    sys.exit(main())
