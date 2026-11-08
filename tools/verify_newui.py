"""Source-level guardrails for the Kotlin + Jetpack Compose UI; runtime behavior is covered by Android CI."""
import re
import json
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
native = ROOT / "app/src/main/java/io/nekohasekai/sfa"
compose = native / "compose"
errors = []

# FK-box: the app UI is Kotlin + Jetpack Compose hosted by BoxActivity; the
# Flutter module was removed once every page was translated.
if (ROOT / "flutter").exists():
    errors.append("flutter/ must stay removed; the UI lives in compose/")
for name in ("MainActivity", "ShareActivity"):
    source = (compose / f"{name}.kt").read_text()
    if f"class {name} : BoxActivity()" not in source:
        errors.append(f"{name} must enter BoxActivity")
if "setContent" not in (native / "bridge/BoxActivity.kt").read_text():
    errors.append("BoxActivity must host the Compose UI")

# Every AppGlyphs reference in the pages must name a defined glyph.
glyph_source = (compose / "ui/AppGlyphs.kt").read_text()
defined = set(re.findall(r"\b(?:val|fun)\s+(\w+)", glyph_source))
for path in compose.rglob("*.kt"):
    if path.name == "AppGlyphs.kt":
        continue
    for name in set(re.findall(r"AppGlyphs\.(\w+)", path.read_text())):
        if name not in defined:
            errors.append(f"{path.relative_to(ROOT)}: unknown glyph {name}")

# No page may be left as a phase placeholder.
for path in (compose / "pages").rglob("*.kt"):
    if re.search(r"moves to Compose in the next phase|TODO\(phase \d\)", path.read_text()):
        errors.append(f"{path.relative_to(ROOT)}: placeholder page remains")

for path in (ROOT / "app/src").rglob("*.kt"):
    source = path.read_text()
    if re.search(r"\bio\.flutter\.|sfa\.compose\.(?:screen|navigation|component)\b|sfa\.update\b", source):
        errors.append(f"{path.relative_to(ROOT)}: Flutter embedding or retired UI/update dependency")
for path in (ROOT / "app/build.gradle.kts", ROOT / "build.gradle.kts", ROOT / "settings.gradle.kts"):
    if re.search(r"flutter_(?:debug|release)|flutter/build|navigation-fragment|sora-editor|app-update-ktx", path.read_text()):
        errors.append(f"{path.name}: Flutter AAR or retired UI/update build dependency")
for path in (ROOT / ".github").rglob("*.yml"):
    if re.search(r"setup-flutter|flutter (?:build|analyze|test)|flutter-aar", path.read_text()):
        errors.append(f"{path.relative_to(ROOT)}: workflow still depends on Flutter")
for path in (ROOT / "app/src").rglob("AndroidManifest.xml"):
    if re.search(r"REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT_USER_ACTION|InstallResultReceiver|WRITE_EXTERNAL_STORAGE", path.read_text()):
        errors.append(f"{path.relative_to(ROOT)}: retired installer/QR export permission")
for directory in (native / "update", native / "compat", ROOT / "app/src/github"):
    for path in directory.rglob("*"):
        if path.is_file():
            errors.append(f"{path.relative_to(ROOT)}: retired source remains")
for retired in ("libbox/option/httpdebug.go", "tools/ebpf", "libbox/httpdebug/wire.go",
                "app/src/main/java/io/nekohasekai/sfa/bg/RootCaptureSession.kt"):
    path = ROOT / retired
    if path.is_file() or path.is_dir() and any(item.is_file() for item in path.rglob("*")):
        errors.append(f"{retired}: retired capture implementation remains")

manifest = json.loads((ROOT / "flclash-manifest.json").read_text())
for reference in manifest.get("supplementalReferences", []):
    if not re.fullmatch(r"[0-9a-f]{40}", reference["commit"]):
        errors.append("Supplemental FlClash reference must pin a complete commit")
    for source in reference["files"]:
        if not re.fullmatch(r"[0-9a-f]{64}", source["sourceSHA256"]):
            errors.append(f"Invalid supplemental source hash: {source['source']}")
        for local in source["localPaths"]:
            path = ROOT / local
            if not path.is_file():
                errors.append(f"Missing supplemental reference target: {local}")
            elif source["usage"] == "verbatim" and hashlib.sha256(path.read_bytes()).hexdigest() != source["sourceSHA256"]:
                errors.append(f"Changed verbatim supplemental import: {local}")
for license_file in ("LICENSE", "JetBrainsMono-OFL.txt"):
    if not (ROOT / "licenses" / license_file).is_file():
        errors.append(f"licenses/{license_file} must be kept")
if errors:
    raise SystemExit("\n".join(errors))
print("Compose entry points, glyphs, placeholders, Flutter removal and capture removal guards passed")
