"""Source-level guardrails for the Flutter UI and its native host; runtime behavior is covered by Flutter/Android CI."""
import re
import json
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
frontend = ROOT / "flutter/lib"
native = ROOT / "app/src/main/java/io/nekohasekai/sfa"
errors = []

# Every AppGlyphs reference outside the UI kit must name a defined glyph, and
# every relative Dart import must resolve.
glyph_source = (frontend / "ui/icons/app_glyphs.dart").read_text()
for path in frontend.rglob("*.dart"):
    source = path.read_text()
    if "ui" not in path.relative_to(frontend).parts:
        for name in set(re.findall(r"AppGlyphs\.(\w+)", source)):
            if not re.search(r"\b" + name + r"\b", glyph_source):
                errors.append(f"{path.relative_to(ROOT)}: unknown FlClash glyph {name}")
    for target in re.findall(r"^import ['\"]([^'\"]+)", source, re.M):
        if ":" not in target and not (path.parent / target).is_file():
            errors.append(f"{path.relative_to(ROOT)}: missing import {target}")

# FK-box: the UI is the Flutter module, hosted by BoxActivity (a Flutter
# activity) behind the stable MainActivity/ShareActivity component names.
for name in ("MainActivity", "ShareActivity"):
    source = (native / f"compose/{name}.kt").read_text()
    if f"class {name} : BoxActivity()" not in source:
        errors.append(f"{name} must enter BoxActivity")
activity = (native / "bridge/BoxActivity.kt").read_text()
if "FlutterFragmentActivity" not in activity or "BoxChannel(" not in activity:
    errors.append("BoxActivity must host the Flutter UI through BoxChannel")
channel = (native / "bridge/BoxChannel.kt").read_text()
for name in ("io.koko123as.flbox/box", "io.koko123as.flbox/events"):
    if name not in channel or name not in (frontend / "box.dart").read_text():
        errors.append(f"Flutter and the native host must share the channel {name}")
# Every command the Flutter UI sends must be dispatched by BoxChannel.
dispatched = set(re.findall(r'^\s+"([a-zA-Z]+(?:\.[a-zA-Z]+)*)" ->', channel, re.M))
for path in frontend.rglob("*.dart"):
    for name in re.findall(r"\b(?:call|act|launchAct|_call|_act)\(\s*'([a-z]+(?:\.[a-zA-Z]+)+)'", path.read_text()):
        if name not in dispatched:
            errors.append(f"{path.relative_to(ROOT)}: command {name} has no native handler")

# The Compose UI and its build dependencies were replaced by Flutter.
compose = native / "compose"
for path in compose.rglob("*"):
    if path.is_file() and path.name not in ("MainActivity.kt", "ShareActivity.kt"):
        errors.append(f"{path.relative_to(ROOT)}: retired Compose UI source remains")
for path in (ROOT / "app/src").rglob("*.kt"):
    source = path.read_text()
    if re.search(r"\bandroidx\.compose\.|sfa\.compose\.(?!MainActivity\b|ShareActivity\b)\w|sfa\.update\b", source):
        errors.append(f"{path.relative_to(ROOT)}: Compose UI or retired update dependency")
for path in (ROOT / "app/build.gradle.kts", ROOT / "build.gradle.kts", ROOT / "settings.gradle.kts"):
    if re.search(r"androidx\.compose|plugin\.compose|navigation-fragment|sora-editor|app-update-ktx", path.read_text()):
        errors.append(f"{path.name}: Compose or retired UI/update build dependency")
if not re.search(r'flutter_debug|flutter_release', (ROOT / "app/build.gradle.kts").read_text()):
    errors.append("app/build.gradle.kts must consume the Flutter UI AAR")
for path in (ROOT / "app/src").rglob("AndroidManifest.xml"):
    if re.search(r"REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT_USER_ACTION|InstallResultReceiver|WRITE_EXTERNAL_STORAGE", path.read_text()):
        errors.append(f"{path.relative_to(ROOT)}: retired installer/QR export permission")
for directory in (native / "update", native / "compat", ROOT / "app/src/github"):
    for path in directory.rglob("*"):
        if path.is_file():
            errors.append(f"{path.relative_to(ROOT)}: retired source remains")
for retired in ("flutter/lib/pages/capture_rules.dart", "flutter/lib/pages/tls_offsets.dart",
                "libbox/option/httpdebug.go", "tools/ebpf", "libbox/httpdebug/wire.go",
                "app/src/main/java/io/nekohasekai/sfa/bg/RootCaptureSession.kt"):
    path = ROOT / retired
    if path.is_file() or path.is_dir() and any(item.is_file() for item in path.rglob("*")):
        errors.append(f"{retired}: retired capture implementation remains")

manifest = json.loads((ROOT / "flclash-manifest.json").read_text())
for local, sha in manifest.get("files", {}).items():
    path = ROOT / "flutter" / local
    if not path.is_file():
        errors.append(f"Missing FlClash import: flutter/{local}")
    elif hashlib.sha256(path.read_bytes()).hexdigest() != sha:
        errors.append(f"Changed direct FlClash import (record the adaptation in adaptedFiles): flutter/{local}")
for local, record in manifest.get("adaptedFiles", {}).items():
    if not (ROOT / "flutter" / local).is_file():
        errors.append(f"Missing adapted FlClash file: flutter/{local}")
    if not re.fullmatch(r"[0-9a-f]{64}", record["sourceSHA256"]):
        errors.append(f"Invalid adapted source hash: flutter/{local}")
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
for license_file in ("LICENSE", "JetBrainsMono-OFL.txt", "Twemoji-LICENSE.md"):
    if not (ROOT / "licenses" / license_file).is_file():
        errors.append(f"licenses/{license_file} must be kept")
if errors:
    raise SystemExit("\n".join(errors))
print("Flutter imports, glyphs, channel commands, host entry points, Compose removal and capture removal guards passed")
