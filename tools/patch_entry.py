#!/usr/bin/env python3
"""Install fk-box's own Go packages into the matching sing-box checkout.

    python3 tools/patch_entry.py .core            # apply
    python3 tools/patch_entry.py .core --check    # only report whether it fits

Overlays (new directories in the sing-box tree, under its go.mod):

- legacy/core/fkhost/  -> experimental/fkhost  fk-box's host services (no libbox import)
- legacy/core/fkbox/   -> experimental/fkbox   the gomobile entry package (Java
  io.nekohasekai.fkbox.Fkbox), bound into libbox.aar next to libbox
- legacy/core/fkcore/  -> cmd/fk-core          the desktop core process
- legacy/core/fkbind/  -> cmd/fk-bind          the Android build: binds ./experimental/libbox
  and ./experimental/fkbox into one libbox.aar (one .so), without the legacy
  AAR of sing-box's cmd/internal/build_libbox

setup-core runs it right after copying ui_*.go, before the first `go mod
tidy` (ui_*.go import experimental/fkhost); the overlays only import packages
of the sing-box module, so the tidy resolves nothing new for them. Upstream
files are not edited.
"""
import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from patchcheck import check_absent, report  # noqa: E402

LABEL = "patch_entry"
OVERLAYS = [
    ("legacy/core/fkhost", "experimental/fkhost"),
    ("legacy/core/fkbox", "experimental/fkbox"),
    ("legacy/core/fkcore", "cmd/fk-core"),
    ("legacy/core/fkbind", "cmd/fk-bind"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("core", type=Path)
    parser.add_argument("--check", action="store_true", help="only report whether the overlay paths are free")
    arguments = parser.parse_args()
    core = arguments.core.resolve()
    targets = [core / target for _, target in OVERLAYS]
    if arguments.check:
        failures = check_absent(targets, core, LABEL)
        return report(failures, LABEL, "overlay paths are free")
    for source, target in OVERLAYS:
        if not (ROOT / source).is_dir():
            continue
        destination = core / target
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        for file in sorted((ROOT / source).glob("*.go")):
            shutil.copyfile(file, destination / file.name)
    print("Installed " + ", ".join(target for _, target in OVERLAYS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
