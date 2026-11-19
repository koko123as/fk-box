"""Source-level guardrails for the Rust + Slint UI; runtime behavior is covered by Android CI."""
import re
import json
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
native = ROOT / "app/src/main/java/io/nekohasekai/sfa"
compose = native / "compose"
errors = []

# fk-box: the app UI is Rust + Slint (ui-slint/, libfkui.so) hosted by
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

# The Material Design 3 UI (ui/m3-rewrite, flclash.md "Material Design 3 界面约定"):
# opaque tonal surfaces only. No shaders, liquid glass, blur filters or
# backdrop blur anywhere in the Slint sources: a surface that blurs what
# scrolls under it re-reads and filters that content every frame. Floating
# surfaces are kit.slint's opaque Surface (surface-container roles plus an
# elevation shadow from the theme tokens).
slint_ui = ROOT / "ui-slint/ui"
for path in list(slint_ui.rglob("*.slint")) + list((ROOT / "ui-slint/src").rglob("*.rs")):
    if re.search(r"LiquidGlass|RuntimeShader|AGSL|(?<![-\w])blur\(", path.read_text()):
        errors.append(f"{path.relative_to(ROOT)}: shader, liquid glass or blur filter")


def component_spans(source):
    """(name, start, end) of each top-level component in a .slint source."""
    starts = [(m.group(1), m.start()) for m in re.finditer(r"^(?:export )?component (\w[\w-]*)", source, re.M)]
    return [(name, start, starts[i + 1][1] if i + 1 < len(starts) else len(source)) for i, (name, start) in enumerate(starts)]


for path in slint_ui.rglob("*.slint"):
    rel = path.relative_to(ROOT)
    source = path.read_text()
    # No backdrop blur and no translucent "glass" surfaces (the retired Glass
    # component and its tokens): floating surfaces are opaque.
    if re.search(r"backdrop-blur\s*:", source):
        errors.append(f"{rel}: backdrop-blur is not allowed; use kit.slint's opaque Surface")
    if re.search(r"\bGlass\b|glass-(?:opacity|blur|edge|outline)|Theme\.glass\(", source):
        errors.append(f"{rel}: the glass surface was retired; use kit.slint's opaque Surface")
    # Elevation shadows only through the theme's tokens (overlapping surfaces).
    for value in re.findall(r"drop-shadow-(?:blur|offset-x|offset-y|color)\s*:\s*([^;]+);", source):
        if "Theme.shadow-" not in value and not re.fullmatch(r"\s*(?:0px|transparent)\s*", value):
            errors.append(f"{rel}: drop shadow outside the Theme.shadow-* tokens: {value.strip()}")
    # Screens use the semantic color roles; literal colors only in the token
    # file, the colour picker (appearance.slint) and the QR code.
    if path.name not in ("theme.slint", "appearance.slint") and re.search(r"#[0-9a-fA-F]{6}\b", source):
        errors.append(f"{rel}: literal hex color; use a Theme.s color role")
theme = (slint_ui / "theme.slint").read_text()
for token in ("surface-container-lowest", "surface-container-highest", "outline-variant", "on-tertiary-container",
              "corner-extra-small", "corner-extra-large", "state-pressed", "min-target",
              "display-large", "headline-small", "title-medium", "body-small", "label-large"):
    if token not in theme:
        errors.append(f"ui-slint/ui/theme.slint: missing M3 token {token}")
kit = (slint_ui / "kit.slint").read_text()
for component in ("StateLayer", "FocusRing", "Button", "IconButton", "Switch", "FilterChip", "SegmentedButton",
                  "Card", "ListItem", "Field", "SearchField", "TopBar", "Surface"):
    if not re.search(rf"export component {component}\b", kit):
        errors.append(f"ui-slint/ui/kit.slint: missing M3 component {component}")
surface = next(((start, end) for name, start, end in component_spans(kit) if name == "Surface"), None)
if surface and "background: Theme.level(root.level);" not in kit[surface[0]:surface[1]]:
    errors.append("ui-slint/ui/kit.slint: Surface must be the opaque tonal surface role (Theme.level)")
if not re.search(r"export component TopBar inherits Surface\b", kit):
    errors.append("ui-slint/ui/kit.slint: the top app bar must be an opaque Surface (scrolled content under it)")
home = (slint_ui / "home.slint").read_text()
bar = re.search(r"bar := Surface \{(.*?)\n        \}", home, re.S)
if not bar or "Nav.bar-margin" not in home or not re.search(r"elevation:\s*[1-3];", bar.group(1)):
    errors.append("ui-slint/ui/home.slint: the phone navigation bar must be the floating opaque Surface with an elevation shadow")
if "bar-margin + bar-height" not in (slint_ui / "globals.slint").read_text():
    errors.append("ui-slint/ui/globals.slint: home-bottom must clear the floating bar")

# Motion (flclash.md "动效"): every animation takes its duration and curve
# from the Theme motion tokens, which theme.slint multiplies by the system's
# animator duration scale (Android's ANIMATOR_DURATION_SCALE, sent by the host;
# 0 turns motion off); presses ripple through kit.slint's Ripple.
for path in slint_ui.rglob("*.slint"):
    rel = path.relative_to(ROOT)
    source = path.read_text()
    if path.name == "theme.slint":
        continue
    for block in re.finditer(r"animate\s+[\w\-,\s]+\{([^}]*)\}", source):
        body = block.group(1)
        for key, value in re.findall(r"(duration|delay)\s*:\s*([^;]+);", body):
            if re.search(r"(?<![\w.-])(?!0ms\b)\d+(?:\.\d+)?ms\b", value):
                errors.append(f"{rel}: animation {key} hard-coded ({value.strip()}); use a Theme.motion-* token")
            if key == "duration" and "Theme.motion-" not in value:
                errors.append(f"{rel}: animation duration without a Theme.motion-* token: {value.strip()}")
        for value in re.findall(r"easing\s*:\s*([^;]+);", body):
            if "Theme.easing-" not in value or re.search(r"cubic-bezier|\bease(?:-in|-out|-in-out)?\b|\blinear\b", value):
                errors.append(f"{rel}: animation easing outside the Theme.easing-* tokens: {value.strip()}")
    if re.search(r"cubic-bezier\(", source):
        errors.append(f"{rel}: cubic-bezier curve outside theme.slint; use a Theme.easing-* token")
    for match in re.finditer(r"animation-tick\(\)\s*/\s*([^,;)]+)", source):
        if "Theme.motion-" not in match.group(1):
            errors.append(f"{rel}: animation-tick() period hard-coded ({match.group(1).strip()}); use a Theme.motion-* token")
    # Slint's Timer.restart() does nothing on a timer that never ran (running: false
    # until now), so a restart must fall back to start().
    for match in re.finditer(r"([\w-]+)\.restart\(\)", source):
        name = re.escape(match.group(1))
        guard = rf"if \({name}\.running\) \{{ {name}\.restart\(\); \}} else \{{ {name}\.start\(\); \}}"
        if not re.match(guard, source[source.rfind("if (", 0, match.start()):]):
            errors.append(f"{rel}: {match.group(1)}.restart() without the start() fallback "
                          f"(`if ({match.group(1)}.running) {{ ….restart(); }} else {{ ….start(); }}`)")
if not re.search(r"in property <float> motion-scale", theme):
    errors.append("ui-slint/ui/theme.slint: missing the motion-scale token (animator duration scale)")
for name, value in re.findall(r"out property <duration> (motion-[\w-]+):\s*([^;]+);", theme):
    if not name.endswith("-period") and "* motion-scale" not in value:
        errors.append(f"ui-slint/ui/theme.slint: {name} is not multiplied by motion-scale")
for token in ("motion-short", "motion-medium", "motion-long", "easing-standard", "easing-emphasized", "easing-decelerate", "easing-back", "motion-on"):
    if not re.search(rf"\b{token}\b\s*:", theme):
        errors.append(f"ui-slint/ui/theme.slint: missing motion token {token}")
if not re.search(r"export component Ripple\b", kit):
    errors.append("ui-slint/ui/kit.slint: missing the Ripple component")
for component in ("IconButton", "Button", "Card", "ListItem", "Tap"):
    span = next(((start, end) for name, start, end in component_spans(kit) if name == component), None)
    if span and "Ripple {" not in kit[span[0]:span[1]]:
        errors.append(f"ui-slint/ui/kit.slint: {component} has no Ripple")
# Every tappable surface ripples: a TouchArea with a `clicked` handler in the
# pages either sits next to a Ripple it drives (rip.down / rip.up) or is a
# kit.slint Tap; the exceptions are the scrims and the snackbar that dismiss
# (no surface of their own) and a form row's tap that focuses its field,
# listed here by their action.
plain_taps = {"Nav.menu-dismiss()", "Nav.dialog-dismiss()", "Nav.dismiss-toast()", "Nav.dismiss-form(r.id)",
              "ff.focus-end()"}
for path in slint_ui.rglob("*.slint"):
    if path.name == "kit.slint":
        continue
    source = path.read_text()
    for match in re.finditer(r"TouchArea \{", source):
        body = source[match.end():match.end() + 900]
        depth, end = 1, 0
        for i, ch in enumerate(body):
            depth += ch == "{"
            depth -= ch == "}"
            if depth == 0:
                end = i
                break
        body = body[:end]
        clicked = re.search(r"clicked => \{\s*(.*?);?\s*\}", body, re.S)
        if not clicked or ".down(" in body:
            continue
        action = clicked.group(1).strip().rstrip(";")
        if not any(action.startswith(p.rstrip(";")) for p in plain_taps):
            line = source.count("\n", 0, match.start()) + 1
            errors.append(f"{path.relative_to(ROOT)}:{line}: a tappable TouchArea without a ripple; use kit.slint Tap ({action})")
# List motion and the sheet's drag (flclash.md "动效").
for component in ("ListMotion", "Entrance"):
    if not re.search(rf"export component {component}\b", kit):
        errors.append(f"ui-slint/ui/kit.slint: missing the list motion component {component}")
for page in ("pages/rows.slint", "pages/profiles.slint", "pages/proxies.slint"):
    if "ListMotion {" not in (slint_ui / page).read_text():
        errors.append(f"ui-slint/ui/{page}: the list does not animate added and removed items (ListMotion)")
routes = (slint_ui / "routes.slint").read_text()
for token in ("Theme.sheet-close-fraction", "Theme.sheet-fling-velocity", "Theme.sheet-handle"):
    if token not in routes:
        errors.append(f"ui-slint/ui/routes.slint: the phone sheet's drag does not use {token}")
if "set_motion_scale" not in (ROOT / "ui-slint/src/theme_apply.rs").read_text():
    errors.append("ui-slint/src/theme_apply.rs: the motion scale does not reach Theme")
if "ANIMATOR_DURATION_SCALE" not in (native / "bridge/BoxActivity.kt").read_text():
    errors.append("BoxActivity.kt: the host must send Settings.Global.ANIMATOR_DURATION_SCALE")

# Slint 1.19+; the vendored Skia renderer and Android backend are the same
# release as the slint crate (FKBOX-PATCH.md).
cargo = (ROOT / "ui-slint/Cargo.toml").read_text()
slint_version = re.search(r'^slint = \{ version = "=(\d+)\.(\d+)\.(\d+)"', cargo, re.M)
if not slint_version or (int(slint_version.group(1)), int(slint_version.group(2))) < (1, 19):
    errors.append("ui-slint/Cargo.toml: Slint 1.19 or newer is required")
else:
    version = ".".join(slint_version.groups())
    for crate in ("i-slint-renderer-skia", "i-slint-backend-android-activity", "i-slint-core"):
        vendored = ROOT / "ui-slint/vendor" / crate
        if f'version = "{version}"' not in (vendored / "Cargo.toml").read_text():
            errors.append(f"ui-slint/vendor/{crate}: not rebased on Slint {version}")
        if not (vendored / "FKBOX-PATCH.md").read_text().startswith(f"# {crate} {version},"):
            errors.append(f"ui-slint/vendor/{crate}/FKBOX-PATCH.md: does not describe {version}")

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
print("Slint UI entry points, Compose and Flutter removal, Material Design 3 UI, opaque surfaces (no blur), motion and capture removal guards passed")
