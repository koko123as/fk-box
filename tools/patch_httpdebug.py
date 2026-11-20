"""Install B-box's capture engine and bindings into the matching core checkout.

    python3 tools/patch_httpdebug.py .core            # apply
    python3 tools/patch_httpdebug.py .core --check    # only report whether it fits
"""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from patchcheck import check_absent, check_patches, report  # noqa: E402

LABEL = "patch_httpdebug"


def overlay_targets(core):
    """Every file the overlay creates; none may exist upstream."""
    targets = [core / "experimental/httpdebug", core / "route/b_box_httpdebug.go",
               core / "experimental/libbox/ui_capture.go"]
    targets += [core / "route" / ("b_box_" + source.name) for source in (ROOT / "core/route").glob("*.go")]
    for pattern in ("ui_capture*.go", "ui_http_rewrite*.go"):
        targets += [core / "experimental/libbox" / source.name for source in (ROOT / "core").glob(pattern)]
    targets += [core / "option" / source.name for source in (ROOT / "core/option").glob("*.go")]
    return sorted(set(targets))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("core", type=Path)
    parser.add_argument("--check", action="store_true", help="only report whether the patches and overlay fit")
    arguments = parser.parse_args()
    core = arguments.core.resolve()
    patch = ROOT / "core/httpdebug.patch"
    rewrite_patch = ROOT / "core/http_rewrite.patch"
    if arguments.check:
        failures = check_absent(overlay_targets(core), core, LABEL)
        failures += check_patches(core, [patch, rewrite_patch], LABEL)
        return report(failures, LABEL, "httpdebug.patch and http_rewrite.patch apply; overlay paths are free")
    targets = [core / "experimental/httpdebug", core / "route/b_box_httpdebug.go",
               core / "experimental/libbox/ui_capture.go"]
    if any(path.exists() for path in targets):
        raise SystemExit("Capture overlay already exists; use a clean matching core checkout")
    subprocess.run(["git", "apply", "--check", str(patch)], cwd=core, check=True)
    subprocess.run(["git", "apply", str(patch)], cwd=core, check=True)
    subprocess.run(["git", "apply", "--check", str(rewrite_patch)], cwd=core, check=True)
    subprocess.run(["git", "apply", str(rewrite_patch)], cwd=core, check=True)
    shutil.copytree(ROOT / "core/httpdebug", targets[0])
    for source in (ROOT / "core/route").glob("*.go"):
        shutil.copyfile(source, core / "route" / ("b_box_" + source.name))
    for source in (ROOT / "core").glob("ui_capture*.go"):
        shutil.copyfile(source, core / "experimental/libbox" / source.name)
    for source in (ROOT / "core").glob("ui_http_rewrite*.go"):
        shutil.copyfile(source, core / "experimental/libbox" / source.name)
    for source in (ROOT / "core/option").glob("*.go"):
        shutil.copyfile(source, core / "option" / source.name)
    print("Installed HTTP/MITM capture, profile rewriting, route hooks and libbox bindings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
