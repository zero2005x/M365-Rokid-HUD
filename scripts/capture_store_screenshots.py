"""Capture Play Store screenshots of the phone app in every supported locale.

Drives a debug build on an emulator with adb + uiautomator, using Demo Ride so
no scooter is needed, and writes doc/play-store/<play-console-locale>/*.png.

Prerequisites
  - A running emulator (API 34+) with the debug APK installed:
        ./gradlew :app:installDebug          (add -PskipRustBuild without Rust)
  - ANDROID_HOME pointing at the SDK.

Usage
  python scripts/capture_store_screenshots.py [--serial emulator-5554] [locale ...]

Locales use resource qualifiers (en, zh-rTW, ar, ...); default is all 11.

Notes
  - Buttons are found by their localized string resources, so the script
    breaks loudly (not silently) if a label changes.
  - Demo data carries no model name, and the details screen hides every
    capability-gated section for an unknown model, so the script selects
    "Xiaomi M365" in the model override first.
  - On a GPU-accelerated emulator under host load, screencap can keep
    returning a stale frame. If captures time out on "screen did not change",
    restart the emulator with -gpu swiftshader_indirect.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PKG = "com.m365bleapp"
ADB = os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools",
                   "adb.exe" if os.name == "nt" else "adb")

# Resource qualifier -> Play Console listing language code.
PLAY_LOCALES = {
    "en": "en-US", "zh-rTW": "zh-TW", "zh-rCN": "zh-CN", "ja": "ja-JP", "ko": "ko-KR",
    "es": "es-ES", "fr": "fr-FR", "it": "it-IT", "ru": "ru-RU", "uk": "uk", "ar": "ar",
}

serial = None
_last_png = None


def adb(*args, input=None, check=True):
    r = subprocess.run([ADB, "-s", serial, *args], input=input, capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args}: {r.stderr.decode(errors='replace')}")
    return r.stdout


def prepare_device():
    """9:16 portrait at a phone density, and a clean demo-mode status bar."""
    adb("shell", "wm", "size", "1080x1920")
    adb("shell", "wm", "density", "360")
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for extra in (
        ["command", "enter"],
        ["command", "clock", "-e", "hhmm", "0930"],
        ["command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
        ["command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true",
         "-e", "mobile", "hide"],
        ["command", "notifications", "-e", "visible", "false"],
    ):
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", *extra)


def strings(loc):
    def load(p):
        return {e.get("name"): "".join(e.itertext()).replace("\\'", "'").replace('\\"', '"')
                for e in ET.parse(p).getroot().iter("string")}
    res = REPO / "app/src/main/res"
    s = load(res / "values/strings.xml")
    if loc != "en":
        s.update(load(res / f"values-{loc}/strings.xml"))
    return s


def set_locale(loc):
    """Write the app's own language preference (see LocaleHelper) via run-as."""
    code, _, country = loc.partition("-r")
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           f'    <string name="app_language">{code}</string>\n'
           f'    <string name="app_country">{country}</string>\n</map>\n')
    adb("shell", "am", "force-stop", PKG)
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/language_prefs.xml'",
        input=xml.encode())


def dump():
    for _ in range(3):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False).decode("utf-8", "replace")
        i, j = raw.find("<?xml"), raw.rfind("</hierarchy>")
        if i >= 0 and j > 0:
            return ET.fromstring(raw[i:j + len("</hierarchy>")])
        time.sleep(1)
    raise RuntimeError("uiautomator dump failed")


def centre(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(node):
    x, y = centre(node)
    adb("shell", "input", "tap", str(x), str(y))


def find(label):
    root = dump()
    for n in root.iter("node"):
        # A freshly booted emulator often shows "System UI isn't responding".
        if n.get("resource-id") == "android:id/aerr_wait":
            tap(n)
            time.sleep(1.5)
            root = dump()
            break
    want = label.casefold()  # section headers are rendered upper-case
    for n in root.iter("node"):
        if (n.get("text") or "").casefold() == want or (n.get("content-desc") or "").casefold() == want:
            return n
    return None


def tap_label(label, scroll=True, tries=6):
    for _ in range(tries):
        n = find(label)
        if n is not None:
            tap(n)
            time.sleep(1.2)
            return
        if not scroll:
            break
        adb("shell", "input", "swipe", "540", "1500", "540", "700", "300")
        time.sleep(0.8)
    raise RuntimeError(f"not found on screen: {label!r}")


def wait_for(label, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        if find(label) is not None:
            time.sleep(1.5)  # let the transition settle
            return
        time.sleep(0.7)
    raise RuntimeError(f"timed out waiting for {label!r}")


def back():
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(1.2)


def to_top():
    for _ in range(3):
        adb("shell", "input", "swipe", "540", "500", "540", "1700", "200")
    time.sleep(0.6)


def shot(path):
    """Screencap, retrying while the frame is identical to the previous one."""
    global _last_png
    path.parent.mkdir(parents=True, exist_ok=True)
    for _ in range(8):
        png = adb("exec-out", "screencap", "-p")
        if png != _last_png:
            break
        time.sleep(2)
    else:
        raise RuntimeError(f"screen did not change for {path}")
    _last_png = png
    path.write_bytes(png)
    print("  ", path.relative_to(REPO))


def capture(loc):
    s = strings(loc)
    out = REPO / "doc/play-store" / PLAY_LOCALES[loc]
    print(loc)
    set_locale(loc)
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    wait_for(s["scan_scanning"])

    tap_label(s["model_override_title"], scroll=False)
    wait_for(s["model_override_explanation"])
    tap_label("Xiaomi M365", scroll=False)
    time.sleep(1)
    if find(s["model_override_explanation"]) is not None:
        back()

    tap_label(s["settings_title"], scroll=False)
    wait_for(s["settings_section_display"])
    tap_label(s["demo_title"])
    wait_for(s["demo_running"])
    to_top()
    wait_for(s["settings_section_display"])
    shot(out / "04_settings.png")

    tap_label(s["hud_display_title"])
    wait_for(s["hud_field_scooter_battery"])
    shot(out / "03_glasses_display.png")
    back()

    wait_for(s["settings_section_display"])
    tap_label(s["language_title"])
    wait_for(s["language_available_section"])
    shot(out / "05_language.png")
    back()

    wait_for(s["settings_section_display"])
    back()
    wait_for(s["dashboard_view_details"])
    time.sleep(2)  # let the demo ride produce a few samples
    shot(out / "01_dashboard.png")

    tap_label(s["dashboard_view_details"])
    wait_for(s["info_speed_section"])
    shot(out / "02_details.png")
    back()


def main():
    global serial
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("locales", nargs="*", default=list(PLAY_LOCALES))
    args = ap.parse_args()
    unknown = [l for l in args.locales if l not in PLAY_LOCALES]
    if unknown:
        sys.exit(f"unknown locale(s): {unknown}; expected one of {list(PLAY_LOCALES)}")
    serial = args.serial

    prepare_device()
    for loc in args.locales:
        capture(loc)
    adb("shell", "am", "force-stop", PKG)


if __name__ == "__main__":
    main()
