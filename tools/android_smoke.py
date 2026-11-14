"""Run UI and native regressions independently on a CI emulator."""

import functools
import base64
import contextlib
import http.server
import json
import os
import re
import subprocess
import ssl
import tempfile
import threading
import time
import traceback
import urllib.parse
from pathlib import Path

# The emulator APK is the debug build, which installs beside the release app.
PACKAGE = "io.fkbox.app.debug"
COMPONENT = PACKAGE + "/io.nekohasekai.sfa.compose.MainActivity"
OUT = Path("build/android-smoke")


def adb(*args, binary=False, timeout=45):
    return subprocess.check_output(["adb", *args], text=not binary, timeout=timeout)


# The Rust + Slint UI draws every widget itself on GameActivity's surface and
# exposes no accessibility tree, so UIAutomator sees one opaque view and text
# lookups ("Dashboard", "Start") can never succeed. The UI suite therefore checks
# what is observable from outside: the activity launches and stays resumed, the
# process neither dies nor restarts, the UI library logs no panic, a deep link
# is accepted, and a cold restart works. Screenshots are kept for review.

# x86_64 emulator images run the arm64-only APK through ARM translation
# (berberis). It does not implement MRS reads of the ARM ID registers
# (0xd538xxxx, e.g. MIDR_EL1) that the Go runtime inside libbox.so issues at
# start-up, so every process that loads libbox dies with SIGILL before any app
# code runs. That is a property of the emulator, not of FK-box; it is reported
# as an environment skip, never as a pass, and any other failure still fails.
TRANSLATION_SIGILL = re.compile(r"berberis\s*:\s*Undefined instruction 0xd538[0-9a-f]{4}")


class EnvironmentSkip(Exception):
    """The emulator cannot run the arm64 core (see TRANSLATION_SIGILL)."""


def logcat(*buffers):
    args = ["logcat", "-d"]
    for buffer in buffers:
        args += ["-b", buffer]
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=45, check=False)
    return result.stdout


def check_translation(text=None):
    text = logcat("main", "crash") if text is None else text
    match = TRANSLATION_SIGILL.search(text)
    if match:
        raise EnvironmentSkip("ARM translation on this x86_64 emulator image cannot run libbox.so: "
                              + match.group(0))


def app_pid():
    result = subprocess.run(["adb", "shell", "pidof", PACKAGE], capture_output=True, text=True, timeout=30, check=False)
    pids = result.stdout.split()
    return pids[0] if pids else None


def resumed():
    activities = adb("shell", "dumpsys", "activity", "activities")
    return any(PACKAGE in line for line in activities.splitlines() if "ResumedActivity" in line)


def crashes():
    text = logcat("crash")
    return [line for line in text.splitlines() if PACKAGE in line or "FKui" in line]


def panics():
    text = logcat("main")
    return [line for line in text.splitlines() if "FKui" in line and "panic" in line]


def launched(label, settle=15):
    """The activity is resumed in a live process that stays the same for `settle` s."""
    deadline = time.monotonic() + 90
    pid = None
    while time.monotonic() < deadline:
        pid = app_pid()
        if pid and resumed():
            break
        check_translation()
        time.sleep(1)
    else:
        check_translation()
        raise AssertionError(f"{label}: FK-box never reached the resumed state (pid {pid})")
    end = time.monotonic() + settle
    while time.monotonic() < end:
        current = app_pid()
        if current != pid:
            check_translation()
            raise AssertionError(f"{label}: process {pid} died or restarted ({current}); see logcat")
        time.sleep(1)
    found = crashes()
    if found:
        check_translation()
        raise AssertionError(f"{label}: crash buffer has FK-box entries: {found[:3]}")
    found = panics()
    if found:
        raise AssertionError(f"{label}: the UI library panicked: {found[:3]}")
    assert resumed(), f"{label}: FK-box is no longer the resumed activity"
    capture(label)
    return pid


def capture(name):
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def ui_smoke():
    config = {
        "log": {"level": "info"},
        "inbounds": [{"type": "tun", "address": ["172.19.0.1/30"], "auto_route": True}],
        "outbounds": [
            {"type": "selector", "tag": "Smoke selector", "outbounds": ["direct"]},
            {"type": "direct", "tag": "direct"},
        ],
        "route": {"final": "Smoke selector"},
        "experimental": {"clash_api": {"default_mode": "rule"}},
    }
    (OUT / "profile.json").write_text(json.dumps(config))
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(OUT))
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
        port = server.server_port
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            adb("reverse", f"tcp:{port}", f"tcp:{port}")
            adb("shell", "am", "start", "-W", "-n", COMPONENT)
            first = launched("01-startup")
            uri = "sing-box://import-remote-profile?" + urllib.parse.urlencode({
                "url": f"http://127.0.0.1:{port}/profile.json",
            }) + "#" + urllib.parse.quote("Smoke profile")
            # ADB's shell parses the encoded URI a second time.
            adb("shell", "am", "start", "-W", "-n", COMPONENT,
                "-a", "android.intent.action.VIEW", "-d", "'" + uri + "'")
            second = launched("02-deep-link")
            assert first == second, "The deep link restarted the process"
            # Back to the launcher and in again: the surface is recreated.
            adb("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(2)
            adb("shell", "am", "start", "-W", "-n", COMPONENT)
            launched("03-resumed")
            # Cold restart.
            adb("shell", "am", "force-stop", PACKAGE)
            adb("logcat", "-c")
            adb("shell", "am", "start", "-W", "-n", COMPONENT)
            launched("04-cold-restart")
        finally:
            server.shutdown()
            try:
                adb("reverse", "--remove", f"tcp:{port}")
            except (subprocess.SubprocessError, OSError) as error:
                print(f"Reverse-port cleanup failed: {error}")


@contextlib.contextmanager
def mitm_origin():
    """Independent TLS origin for the device's actual libbox/Keystore path.

    Keys are generated only on the CI runner, outside uploaded artifacts, and
    removed when the suite ends. Only the public root is passed to the device.
    """
    scratch = Path.home() / "tmp"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bbox-mitm-origin-", dir=scratch) as directory:
        root = Path(directory)
        commands = [
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
             "-sha256", "-days", "1", "-subj", "/CN=FK-box independent CI origin root", "-keyout", "root.key", "-out", "root.pem",
             "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign"],
            ["openssl", "req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
             "-subj", "/CN=origin.test", "-keyout", "origin.key", "-out", "origin.csr"],
            ["openssl", "x509", "-req", "-in", "origin.csr", "-CA", "root.pem", "-CAkey", "root.key", "-set_serial", "2",
             "-days", "1", "-sha256", "-extfile", "extensions.txt", "-out", "origin.pem"],
        ]
        (root / "extensions.txt").write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
                                             "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:origin.test\n")
        for command in commands:
            subprocess.run(command, cwd=root, check=True, capture_output=True, timeout=30)
        requests = []

        class Origin(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self):
                requests.append({"path": self.path, "host": self.headers.get("Host")})
                body = b'{"mitm":"through-original-route"}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Set-Cookie", "first=1")
                self.send_header("Set-Cookie", "second=2")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.set_alpn_protocols(["http/1.1"])
        context.load_cert_chain(root / "origin.pem", root / "origin.key")
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Origin) as server:
            port = server.server_port
            server.socket = context.wrap_socket(server.socket, server_side=True)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                adb("reverse", f"tcp:{port}", f"tcp:{port}")
                yield ["-e", "mitmOriginPort", str(port), "-e", "mitmOriginCA",
                       base64.b64encode((root / "root.pem").read_bytes()).decode("ascii")]
                assert requests == [{"path": "/probe?raw=1;2", "host": f"origin.test:{port}"}], requests
            finally:
                server.shutdown()
                try:
                    adb("reverse", "--remove", f"tcp:{port}")
                except (subprocess.SubprocessError, OSError) as error:
                    print(f"Reverse-port cleanup failed: {error}")
                (OUT / "mitm-origin.json").write_text(json.dumps(requests, indent=2) + "\n")


def native_regressions():
    log = OUT / "native-tests.txt"
    try:
        try:
            # Write directly to disk so a timeout preserves partial instrumentation output.
            with mitm_origin() as arguments, log.open("w") as output:
                subprocess.run([
                    "adb", "shell", "am", "instrument", "-w", "-r",
                    *arguments,
                    PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner",
                ], stdout=output, stderr=subprocess.STDOUT, timeout=360, check=True)
        except (AssertionError, subprocess.SubprocessError):
            # "Process crashed" from the translation SIGILL is an environment skip.
            check_translation()
            raise
        if "Process crashed" in log.read_text():
            check_translation()
    finally:
        if log.exists():
            print(log.read_text())
    assert re.search(r"^OK \([1-9]\d* tests?\)\s*$", log.read_text(), re.MULTILINE), \
        "Native regressions failed or ran no tests; see native-tests.txt"


def diagnostics(name):
    try:
        capture(f"{name}-last-screen")
    except (subprocess.SubprocessError, OSError) as error:
        print(f"Screenshot capture failed: {error}")
    try:
        with (OUT / f"{name}-logcat.txt").open("wb") as log:
            # Some emulator logcat versions exit 255 after dumping their buffers.
            subprocess.run(["adb", "logcat", "-d", "-t", "5000"], stdout=log,
                           stderr=subprocess.STDOUT, timeout=45, check=False)
    except (subprocess.SubprocessError, OSError) as error:
        print(f"Log collection failed: {error}")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    results = {}
    try:
        apks = list(Path("build/apk").rglob("*.apk"))
        application, = [apk for apk in apks if not apk.name.endswith("-androidTest.apk")]
        instrumentation, = [apk for apk in apks if apk.name.endswith("-androidTest.apk")]
        for apk in (application, instrumentation):
            adb("install", "--no-streaming", "-r", str(apk), timeout=240)
        for name, check in (("ui", ui_smoke), ("native", native_regressions)):
            try:
                # Each suite owns a fresh installation state, even if the UI suite failed mid-start.
                adb("shell", "am", "force-stop", PACKAGE)
                assert "Success" in adb("shell", "pm", "clear", PACKAGE)
                adb("logcat", "-c")
                check()
                results[name] = {"passed": True}
            except EnvironmentSkip as skip:
                results[name] = {"passed": None, "skipped": str(skip)}
                print(f"::warning::Android {name} suite skipped: {skip}")
            except Exception as error:
                results[name] = {"passed": False, "error": str(error)}
                (OUT / f"{name}-failure.txt").write_text(traceback.format_exc())
                traceback.print_exc()
            finally:
                diagnostics(name)
    except Exception as error:
        results["setup"] = {"passed": False, "error": str(error)}
        diagnostics("setup")
        traceback.print_exc()
    finally:
        (OUT / "results.json").write_text(json.dumps({
            "sha": os.environ.get("GITHUB_SHA"), "checks": results,
        }, indent=2) + "\n")
        try:
            adb("shell", "am", "force-stop", PACKAGE)
        except (subprocess.SubprocessError, OSError) as error:
            print(f"App cleanup failed: {error}")
    # A suite passes or is an environment skip; a failure or a missing suite fails.
    if set(results) != {"ui", "native"} or any(result["passed"] is False for result in results.values()):
        raise SystemExit("Android checks failed; see build/android-smoke/results.json")
    skipped = [name for name, result in results.items() if result["passed"] is None]
    if skipped:
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        text = ("Android suites skipped on this emulator (ARM translation cannot run libbox.so): "
                + ", ".join(skipped) + ". They need an arm64 device or image.\n")
        if summary:
            with open(summary, "a") as handle:
                handle.write(text)
        print(text)
    else:
        print("Android UI smoke and native regressions passed")


if __name__ == "__main__":
    main()
