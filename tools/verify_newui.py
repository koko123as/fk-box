"""Source-level guardrails for the Rust + Slint UI; runtime behavior is covered by Android CI."""
import re
import json
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
native = ROOT / "app/src/main/java/io/nekohasekai/sfa"
compose = native / "compose"
errors = []

# FK-box: the app UI is Rust + Slint (ui-slint/, libfkui.so) hosted by
# BoxActivity (GameActivity). compose/ keeps only the stable entry points and
# the QR camera analyzer; the Jetpack Compose UI and the Flutter module are gone.
if (ROOT / "flutter").exists():
    errors.append("flutter/ must stay removed; the UI lives in ui-slint/")
for name in ("MainActivity", "ShareActivity"):
    source = (compose / f"{name}.kt").read_text()
    if f"class {name} : BoxActivity()" not in source:
        errors.append(f"{name} must enter BoxActivity")
if "GameActivity()" not in (native / "bridge/BoxActivity.kt").read_text():
    errors.append("BoxActivity must host the Slint UI (GameActivity)")
if not (ROOT / "ui-slint/ui/app.slint").is_file():
    errors.append("ui-slint/ui/app.slint: the Slint UI is missing")
allowed = {"MainActivity.kt", "ShareActivity.kt"}
for path in compose.rglob("*.kt"):
    rel = path.relative_to(compose)
    if rel.parts[0] != "qr" and path.name not in allowed:
        errors.append(f"{path.relative_to(ROOT)}: the Compose UI was replaced by ui-slint/")
for path in (ROOT / "app/src").rglob("*.kt"):
    if re.search(r"^import androidx\.compose\.", path.read_text(), re.M):
        errors.append(f"{path.relative_to(ROOT)}: Jetpack Compose import")
if re.search(r"androidx\.compose|kotlin\.plugin\.compose|compose\s*=\s*true|graphics-shapes", (ROOT / "app/build.gradle.kts").read_text()):
    errors.append("app/build.gradle.kts: Compose or retired shape dependency")

# The plain UI: no glass, shaders or blur in the Slint sources.
for path in list((ROOT / "ui-slint/ui").rglob("*.slint")) + list((ROOT / "ui-slint/src").rglob("*.rs")):
    if re.search(r"LiquidGlass|RuntimeShader|AGSL|\bblur\(", path.read_text()):
        errors.append(f"{path.relative_to(ROOT)}: glass, shader or blur effect")

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
for license_file in ("LICENSE",):
    if not (ROOT / "licenses" / license_file).is_file():
        errors.append(f"licenses/{license_file} must be kept")
if errors:
    raise SystemExit("\n".join(errors))
print("Slint UI entry points, Compose and Flutter removal, plain UI and capture removal guards passed")
