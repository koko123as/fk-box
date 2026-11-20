#!/usr/bin/env python3
"""Dry-run fk-box's sing-box patches and say exactly which part no longer fits.

Shared by the patch scripts' --check mode (patch_sing_tun.py,
patch_httpdebug.py, patch_dns_count.py, patch_entry.py) and usable on its own
for the patches setup-core applies directly:

    python3 tools/patchcheck.py .core core/libbox-no-legacy.patch

Nothing in the checked tree is modified: patches are tried on copies of the
files they touch in a temporary directory. When a patch does not apply, every
hunk is tried on its own, and each failure names the file, the hunk number,
its @@ header and the first line it changes.
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def patch_files(text):
    """The files a unified diff touches, as (old path, new path)."""
    files = []
    for match in re.finditer(r"^diff --git a/(\S+) b/(\S+)$", text, re.M):
        files.append((match.group(1), match.group(2)))
    return files


def split_hunks(text):
    """Yields (file, number, header, single-hunk patch) for every hunk."""
    sections = re.split(r"(?=^diff --git )", text, flags=re.M)
    for section in sections:
        if not section.startswith("diff --git "):
            continue
        head, _, rest = section.partition("\n@@")
        if not rest:
            continue
        name = re.match(r"diff --git a/(\S+) b/(\S+)", head).group(2)
        hunks = re.split(r"(?=^@@)", "@@" + rest, flags=re.M)
        for number, hunk in enumerate((h for h in hunks if h.startswith("@@")), 1):
            header = hunk.splitlines()[0]
            yield name, number, header, head.rstrip("\n") + "\n" + hunk


def first_change(hunk):
    for line in hunk.splitlines()[1:]:
        if line.startswith(("-", "+")) and not line.startswith(("---", "+++")):
            return line[:100]
    return ""


def git_apply(directory, patch_text, check):
    arguments = ["git", "apply"]
    if check:
        arguments.append("--check")
    return subprocess.run(arguments + ["-"], input=patch_text, text=True, cwd=directory,
                          capture_output=True)


def stage(tree, patches, scratch):
    """Copies every file the patches touch from tree into scratch."""
    for text in patches:
        for old, new in patch_files(text):
            for name in {old, new}:
                source = Path(tree) / name
                if source.is_file():
                    target = Path(scratch) / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)


def check_patches(tree, patch_paths, label):
    """Tries the patches in order on a copy of the files they touch in tree.
    Returns failure lines; empty when all apply. A patch that fails stops
    the sequence (later patches build on it)."""
    failures = []
    texts = [Path(path).read_text() for path in patch_paths]
    with tempfile.TemporaryDirectory(prefix="fkbox-patch-check-") as scratch:
        stage(tree, texts, scratch)
        for path, text in zip(patch_paths, texts):
            name = Path(path).name
            if git_apply(scratch, text, check=True).returncode == 0:
                git_apply(scratch, text, check=False)
                continue
            for file, number, header, hunk in split_hunks(text):
                if not (Path(scratch) / file).exists() and "\nnew file mode" not in hunk.split("\n@@", 1)[0]:
                    failures.append(f"{label}: {name}: {file}: file not found in the core")
                    break
                if git_apply(scratch, hunk, check=True).returncode != 0:
                    change = first_change(hunk)
                    failures.append(f"{label}: {name}: {file}: hunk {number} does not apply: {header}"
                                    + (f" (first change: {change})" if change else ""))
            if not failures:
                failures.append(f"{label}: {name}: hunks apply one by one but not together; rebase the patch")
            break
    return failures


def check_anchor(text, anchor, file, label, count=1):
    """An exact text the script edits must occur `count` times."""
    found = text.count(anchor)
    if found == count:
        return []
    first = anchor.strip().splitlines()[0][:100]
    return [f"{label}: {file}: expected {count} occurrence(s) of the anchor, found {found}: {first}"]


def check_absent(paths, root, label):
    """Overlay targets must not exist upstream (the overlay would replace them)."""
    return [f"{label}: {Path(path).relative_to(root)}: already exists in the core; the overlay would replace an upstream file"
            for path in paths if Path(path).exists()]


def report(failures, label, what):
    """Prints the result and returns the exit status."""
    for failure in failures:
        print(f"::error::{failure}")
    if failures:
        print(f"{label}: --check failed ({len(failures)} problem(s))")
        return 1
    print(f"{label}: --check OK: {what}")
    return 0


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    tree = Path(argv[0]).resolve()
    failures = check_patches(tree, [Path(p).resolve() for p in argv[1:]], "patchcheck")
    return report(failures, "patchcheck", ", ".join(Path(p).name for p in argv[1:]) + " apply")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
