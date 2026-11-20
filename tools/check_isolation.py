#!/usr/bin/env python3
"""Fail when one client reaches into another client's folder.

Every client lives in clients/<name>/ and may only depend on shared code
(core/, ui/, protocol/, version.properties, tools/). A file under clients/<a>/
that names clients/<b>/ (or ../<b>/ from the clients root) breaks isolation.
Also checks that each client has its own .github/workflows/build-<name>.yml
or, for android, the existing build-apk.yml.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENTS = ROOT / "clients"
SKIP_DIRS = {".git", ".gradle", "build", "target", ".cxx", ".idea", "node_modules"}
TEXT_SUFFIXES = {".kt", ".kts", ".java", ".xml", ".gradle", ".properties", ".toml", ".rs",
                 ".slint", ".swift", ".py", ".sh", ".json", ".yml", ".yaml", ".pro", ".cfg",
                 ".txt", ".c", ".h", ".cpp", ".go", ".ts", ".js", ".cmake"}
# Workflow that builds each client (android predates the build-<name>.yml rule).
WORKFLOW_FOR = {"android": "build-apk.yml"}

clients = sorted(p.name for p in CLIENTS.iterdir() if p.is_dir()) if CLIENTS.is_dir() else []
errors = []

for name in clients:
    others = [o for o in clients if o != name]
    workflow = ROOT / ".github" / "workflows" / WORKFLOW_FOR.get(name, f"build-{name}.yml")
    if not workflow.is_file():
        errors.append(f"clients/{name}: missing workflow {workflow.relative_to(ROOT)}")
    elif f"clients/{name}/**" not in workflow.read_text():
        errors.append(f"{workflow.relative_to(ROOT)}: paths filter must include 'clients/{name}/**'")
    if not others:
        continue
    alt = "|".join(re.escape(o) for o in others)
    pattern = re.compile(rf"clients[/\\](?:{alt})(?:[/\\]|\b)|\.\.[/\\](?:\.\.[/\\])*(?:{alt})[/\\]")
    for path in (CLIENTS / name).rglob("*"):
        if not path.is_file() or SKIP_DIRS.intersection(path.relative_to(CLIENTS).parts):
            continue
        if path.suffix not in TEXT_SUFFIXES and path.name not in {"CMakeLists.txt", "Makefile"}:
            continue
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if pattern.search(line):
                errors.append(f"{path.relative_to(ROOT)}:{lineno}: references another client: {line.strip()[:120]}")

if errors:
    print("Client isolation check failed:", *errors, sep="\n  ")
    sys.exit(1)
print(f"Client isolation OK ({', '.join(clients) or 'no clients'})")
