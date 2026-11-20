#!/usr/bin/env python3
"""Check that legacy/core/core_touch_singbox.go lists every sing-box symbol fk-box uses.

    python3 tools/verify_touchpoints.py                # package-qualified symbols
    python3 tools/verify_touchpoints.py --core .core   # plus libbox's own (after setup-core)

The touchpoint file references each sing-box, sing-tun and libbox symbol the
Go code in core/ uses, so an upgrade that changes one fails to compile there,
by name. This check keeps the list complete:

- every `alias.Symbol` in core/ whose import is a sing-box package (or
  sing-tun) must appear in the touchpoint file with the same package;
- with --core, every top-level libbox identifier (from the checked-out
  experimental/libbox, fk-box's own core_*.go/ui_*.go files left out) that
  fk-box's libbox files use unqualified must appear in it too.

fk-box's own overlay packages (experimental/httpdebug, the DNS counter in
dns/, experimental/fkhost and experimental/fkbox) are not upstream symbols and
are skipped, and so is the Android build command legacy/core/fkbind; legacy/core/fkbox and legacy/core/fkcore use libbox by import path, and only the
libbox symbols fk-box does not define itself are checked. Matching is textual (comments,
strings and composite-literal keys are ignored), not a type check: the
compiler does that when the touchpoint file is built.
"""
import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORE = ROOT / "legacy" / "core"
TOUCH = CORE / "core_touch_singbox.go"
UPSTREAM = ("github.com/sagernet/sing-box", "github.com/sagernet/sing-tun")
# fk-box's own packages and symbols inside upstream import paths.
OWN = {
    "github.com/sagernet/sing-box/experimental/httpdebug": None,  # whole package
    "github.com/sagernet/sing-box/dns": {"UIQueryCount"},
    # fk-box's own overlay packages (tools/patch_entry.py).
    "github.com/sagernet/sing-box/experimental/fkhost": None,
    "github.com/sagernet/sing-box/experimental/fkbox": None,
}
# fk-box's packages outside libbox (legacy/core/fkbox, legacy/core/fkcore) reach libbox by
# its import path; symbols fk-box defines there (legacy/core/*.go) are its own, the
# rest are libbox's and are listed unqualified in the touchpoint file.
LIBBOX = "github.com/sagernet/sing-box/experimental/libbox"


def strip(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"`[^`]*`", "``", text)
    text = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', text)
    return re.sub(r"'(?:[^'\\\n]|\\.)*'", "''", text)


def imports(text):
    found = {}
    blocks = re.findall(r"^import \((.*?)^\)", text, re.S | re.M)
    blocks += [line for line in re.findall(r"^import (.*)$", text, re.M) if not line.startswith("(")]
    for block in blocks:
        for match in re.finditer(r'(?:(\w+|\.|_)\s+)?"([^"]+)"', block):
            alias, path = match.group(1), match.group(2)
            if alias in ("_", "."):
                continue
            name = alias or path.rsplit("/", 1)[-1]
            if path == "github.com/sagernet/sing-tun" and not alias:
                name = "tun"
            found[name] = path
    return found


def body(text):
    match = re.search(r"^import \(.*?^\)", text, re.S | re.M)
    return strip(text[match.end():] if match else text)


# fk-box's Android build command (legacy/core/fkbind -> cmd/fk-bind) uses sing-box's
# build helpers (cmd/internal/build_shared), which libbox cannot import; it is
# compiled on its own (newui.yml vets it) and names a changed helper there.
BUILD_TOOL = CORE / "fkbind"


def own_file(path):
    return path.name.endswith("_test.go") or path.name == TOUCH.name or BUILD_TOOL in path.parents


def qualified_uses():
    uses = defaultdict(set)
    for path in sorted(CORE.rglob("*.go")):
        if own_file(path):
            continue
        text = path.read_text()
        code = body(text)
        for name, module in imports(text).items():
            if not module.startswith(UPSTREAM):
                continue
            own = OWN.get(module, set())
            if module in OWN and own is None:
                continue
            for symbol in re.findall(r"\b" + re.escape(name) + r"\.([A-Z]\w*)", code):
                if symbol not in own:
                    uses[(module, symbol)].add(path.relative_to(ROOT).as_posix())
    return uses


def top_level(text):
    # Functions without a receiver: methods are reached through their type.
    names = set(re.findall(r"^func (\w+)", text, re.M))
    names |= set(re.findall(r"^type (\w+)", text, re.M))
    for keyword in ("var", "const", "type"):
        for block in re.findall(r"^" + keyword + r" \((.*?)^\)", text, re.S | re.M):
            names |= set(re.findall(r"^\t(\w+)", block, re.M))
        names |= set(re.findall(r"^" + keyword + r" (\w+)", text, re.M))
    return names


def libbox_uses(core_dir):
    libbox = core_dir / "experimental/libbox"
    ours = {path.name for path in CORE.glob("*.go")}
    upstream = set()
    for path in libbox.glob("*.go"):
        if path.name in ours or path.name.endswith("_test.go"):
            continue
        upstream |= top_level(path.read_text())
    defined = set()
    files = [path for path in CORE.glob("*.go") if not own_file(path)]
    for path in files:
        defined |= top_level(path.read_text())
    candidates = upstream - defined - {"init", "_"}
    uses = defaultdict(set)
    for path in files:
        code = body(path.read_text())
        for name in candidates:
            # Unqualified, and not a composite-literal key or a selector.
            if re.search(r"(?<![\w.])" + re.escape(name) + r"\b(?!\s*:[^=])", code):
                uses[name].add(path.name)
    return uses


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--core", type=Path, help="checked-out sing-box (setup-core's .core)")
    args = parser.parse_args()
    touch_text = TOUCH.read_text()
    touch_imports = {path: name for name, path in imports(touch_text).items()}
    touch_code = body(touch_text)
    missing = []
    uses = qualified_uses()
    ours = set()
    for path in CORE.glob("*.go"):
        if not own_file(path):
            ours |= top_level(path.read_text())
    for (module, symbol), files in sorted(uses.items()):
        if module == LIBBOX:
            if symbol in ours:
                continue
            if not re.search(r"(?<![\w.])" + re.escape(symbol) + r"\b", touch_code):
                missing.append(f"libbox {symbol} (used in {', '.join(sorted(files))})")
            continue
        alias = touch_imports.get(module)
        if alias is None or not re.search(r"\b" + re.escape(alias) + r"\." + re.escape(symbol) + r"\b", touch_code):
            missing.append(f"{module}.{symbol} (used in {', '.join(sorted(files))})")
    checked = len(uses)
    if args.core:
        internal = libbox_uses(args.core.resolve())
        for name, files in sorted(internal.items()):
            if not re.search(r"(?<![\w.])" + re.escape(name) + r"\b", touch_code):
                missing.append(f"libbox {name} (used in {', '.join(sorted(files))})")
        checked += len(internal)
    for item in missing:
        print(f"::error file=legacy/core/core_touch_singbox.go::not listed in the touchpoint file: {item}")
    print(f"{checked} sing-box/libbox symbols used by core/, {len(missing)} missing from {TOUCH.name}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
