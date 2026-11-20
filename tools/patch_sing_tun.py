"""Apply B-box's Android ingress-map fix to an isolated sing-tun dependency.

Run after setting up the matching Go toolchain, before testing/building libbox.
The shared Go module cache is never modified.

    python3 tools/patch_sing_tun.py .core            # apply
    python3 tools/patch_sing_tun.py .core --check    # only report whether it fits
"""

import argparse
import json
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

MODULE = "github.com/sagernet/sing-tun"
VERSION = "v0.9.7-0.20261023115453-611d83b7a57b"
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from patchcheck import check_patches, report  # noqa: E402

LABEL = "patch_sing_tun"


def check(core):
    """Reports, without changing anything, whether the core's sing-tun is the
    version the patch was made for and which hunks still apply to it."""
    failures = []
    try:
        dependency = json.loads(subprocess.check_output(
            ["go", "mod", "download", "-json", MODULE], cwd=core, text=True,
        ))
    except (OSError, subprocess.CalledProcessError) as error:
        return [f"{LABEL}: go.mod: cannot resolve {MODULE}: {error}"]
    if dependency.get("Version") != VERSION:
        failures.append(
            f"{LABEL}: go.mod: {MODULE} is {dependency.get('Version')}, "
            f"sing-tun-android-ingress.patch was made for {VERSION}"
        )
    with tempfile.TemporaryDirectory(prefix="fkbox-sing-tun-") as scratch:
        copy = Path(scratch) / "sing-tun"
        shutil.copytree(dependency["Dir"], copy)
        for path in [copy, *copy.rglob("*")]:
            path.chmod(path.stat().st_mode | stat.S_IWUSR)
        failures += check_patches(copy, [ROOT / "legacy/core/sing-tun-android-ingress.patch"], LABEL)
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("core", type=Path, help="matching sing-box checkout")
    parser.add_argument("--check", action="store_true", help="only report whether the patch fits")
    arguments = parser.parse_args()
    core = arguments.core.resolve()
    if arguments.check:
        return report(check(core), LABEL, f"{MODULE} is {VERSION} and the patch applies")
    dependency = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", MODULE], cwd=core, text=True,
    ))
    if dependency.get("Path") != MODULE or dependency.get("Version") != VERSION:
        raise SystemExit(
            f"Rebase sing-tun-android-ingress.patch before changing {MODULE}: "
            f"expected {VERSION}, got {dependency.get('Version')}"
        )

    target = core / ".b-box" / "sing-tun"
    # Refuse an existing copy instead of silently discarding local changes.
    shutil.copytree(dependency["Dir"], target)
    for path in [target, *target.rglob("*")]:
        path.chmod(path.stat().st_mode | stat.S_IWUSR)

    patch = ROOT / "legacy/core/sing-tun-android-ingress.patch"
    for arguments in (["--check"], []):
        subprocess.run(
            ["git", "apply", "--directory=.b-box/sing-tun", *arguments, str(patch)],
            cwd=core, check=True,
        )
    shutil.copyfile(
        ROOT / "legacy/core/sing-tun/redirect_ingress_discard_linux_test.go",
        target / "redirect_ingress_discard_value_linux_test.go",
    )
    subprocess.run(
        ["go", "mod", "edit", f"-replace={MODULE}=./.b-box/sing-tun"],
        cwd=core, check=True,
    )
    print(f"Applied Android ingress-map compatibility fix to {MODULE}@{VERSION}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
