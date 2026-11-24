"""Apply fk-box's Android ingress-map fix to an isolated sing-tun dependency.

    python3 scripts/patch_sing_tun.py apps/android/fkhost   # CI only

Kept in stage 6 (docs/architecture/brain-android.md "sing-tun patch"): sing-tun's
redirect_ingress_discard_android.go checks the value size of netd's ingress
discard BPF map (line 73 at the pinned version) and only accepts the 8-byte
layout; Android builds with a 4-byte value (one interface) then fail auto-redirect.
Auto-redirect is root-only and not wired on the single-kernel path, so fkhost.aar
is built without this patch; Core and source validation applies it to a scratch
copy of the module (the replace is never committed) and runs its test, so the fix
stays ready for the pinned sing-tun when auto-redirect returns.
The shared Go module cache is never modified.
"""

import argparse
import json
import os
import shutil
import stat
import subprocess
from pathlib import Path

MODULE = "github.com/sagernet/sing-tun"
# The sing-tun the patch was last rebased on. A sing-box upgrade may move
# sing-tun (stage 7, docs/architecture/brain-upgrade.md): the patch is then
# only taken when `git apply --check` accepts it on the new version, and the
# run says so; a patch that no longer applies stops here with what to rebase.
REBASED_ON = "v0.9.7-0.20261023115453-611d83b7a57b"
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("core", type=Path, help="Go module that depends on sing-tun (apps/android/fkhost)")
    core = parser.parse_args().core.resolve()
    dependency = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", MODULE], cwd=core, text=True,
    ))
    if dependency.get("Path") != MODULE:
        raise SystemExit(f"go mod download did not return {MODULE}: {dependency}")
    version = dependency.get("Version")

    target = core / ".b-box" / "sing-tun"
    # Refuse an existing copy instead of silently discarding local changes.
    shutil.copytree(dependency["Dir"], target)
    for path in [target, *target.rglob("*")]:
        path.chmod(path.stat().st_mode | stat.S_IWUSR)

    patch = ROOT / "apps/android/core/sing-tun-android-ingress.patch"
    # Apply inside the scratch copy with repository discovery stopped there.
    # Run from a directory of the fk-box checkout (apps/android/fkhost), git apply
    # takes patch paths relative to the fk-box top level and silently skips every
    # path outside the current directory, so nothing was patched. The old
    # setup-core ran this on .core, a separate sing-box checkout, where it worked.
    environment = dict(os.environ, GIT_CEILING_DIRECTORIES=str(target.parent))
    checked = subprocess.run(
        ["git", "apply", "--check", "--verbose", str(patch)],
        cwd=target, env=environment, check=False,
    )
    if checked.returncode != 0:
        raise SystemExit(
            f"::error file=apps/android/core/sing-tun-android-ingress.patch::"
            f"sing-tun-android-ingress.patch does not apply to {MODULE}@{version} "
            f"(rebased on {REBASED_ON}); rebase it on the new sing-tun "
            f"(redirect_ingress_discard_android.go) or drop it if upstream fixed the check"
        )
    if version != REBASED_ON:
        print(f"::warning file=scripts/patch_sing_tun.py::sing-tun moved {REBASED_ON} -> {version}; "
              f"the patch still applies (git apply --check); update REBASED_ON after review")
    subprocess.run(
        ["git", "apply", "--verbose", str(patch)],
        cwd=target, env=environment, check=True,
    )
    if not (target / "redirect_ingress_discard_value_linux.go").is_file():
        raise SystemExit("sing-tun-android-ingress.patch was not applied")
    shutil.copyfile(
        ROOT / "apps/android/core/sing-tun/redirect_ingress_discard_linux_test.go",
        target / "redirect_ingress_discard_value_linux_test.go",
    )
    subprocess.run(
        ["go", "mod", "edit", f"-replace={MODULE}=./.b-box/sing-tun"],
        cwd=core, check=True,
    )
    print(f"Applied Android ingress-map compatibility fix to {MODULE}@{version}")


if __name__ == "__main__":
    main()
