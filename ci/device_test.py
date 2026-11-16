#!/usr/bin/env python3
"""Drives the installed app on an emulator the way a person would, and checks what really happened
on the device (files, preferences, services, notifications) rather than what the screen says.

Usage: device_test.py <output dir>

Needs a rootable emulator image ("default" or "google_apis"), because the checks read the app's
private folder. Screens that update continuously cannot be inspected by uiautomator, so the test
looks up button positions while the job is paused.
"""
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

OUT = sys.argv[1]
ADB = os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb")
PKG = "app.freespacewiper"
DATA = "/data/data/" + PKG
WIPE_DIR = DATA + "/files/wipe"
SERVICE = PKG + "/.WipeService"
ACTION = PKG + ".action."

os.makedirs(OUT, exist_ok=True)
results = []
shot_no = [0]


def adb(*args, timeout=90):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout, errors="replace").stdout


def sh(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)


def read_file(path):
    """A file from the device. Not through "adb shell": on Android 6 that runs in a terminal, and
    the end of the output can get lost when the command exits."""
    raw = subprocess.run([ADB, "exec-out", "cat %s 2>/dev/null" % path], capture_output=True, timeout=60).stdout
    return raw.decode("utf-8", "replace")


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def shot(name):
    shot_no[0] += 1
    path = os.path.join(OUT, "%02d_%s.png" % (shot_no[0], name))
    data = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    with open(path, "wb") as f:
        f.write(data)
    return path


API = int(sh("getprop ro.build.version.sdk").strip())


# ---------------------------------------------------------------- UI

def dump():
    """The current window as a list of uiautomator nodes; empty when the screen will not hold still."""
    for attempt in range(2):
        t0 = time.time()
        said = sh("rm -f /data/local/tmp/ui.xml; uiautomator dump /data/local/tmp/ui.xml 2>&1", timeout=60).strip()
        xml = read_file("/data/local/tmp/ui.xml")
        if xml.strip().startswith("<?xml"):
            try:
                return list(ET.fromstring(xml).iter("node"))
            except ET.ParseError as e:
                log("   (ui dump could not be parsed: %s)" % e)
        else:
            log("   (ui dump failed after %.1f s: %s)" % (time.time() - t0, said[:160]))
        time.sleep(0.5)
    return []


def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", node.get("bounds")))
    return x1, y1, x2, y2


def center(node):
    x1, y1, x2, y2 = bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(nodes, rid=None, text=None, pkg=None):
    for n in nodes:
        if rid and not n.get("resource-id", "").endswith(":id/" + rid):
            continue
        if text and n.get("text") != text:
            continue
        if pkg and n.get("package") != pkg:
            continue
        return n
    return None


def tap_xy(x, y):
    sh("input tap %d %d" % (x, y))


SCREEN_W, SCREEN_H = map(int, re.findall(r"\d+", sh("wm size"))[:2])


def swipe(up):
    x = SCREEN_W // 2
    a, b = int(SCREEN_H * 0.72), int(SCREEN_H * 0.30)
    if not up:
        a, b = b, a
    sh("input swipe %d %d %d %d 250" % (x, a, x, b))
    time.sleep(0.7)


def locate(rid=None, text=None, pkg=None, timeout=15, scroll=True):
    """Finds an element, scrolling the page if needed. Returns its node or None."""
    end = time.time() + timeout
    scrolled = 0
    while True:
        nodes = dump()
        n = find(nodes, rid, text, pkg)
        if n is not None:
            _, cy = center(n)
            if int(SCREEN_H * 0.08) < cy < int(SCREEN_H * 0.92) or time.time() > end:
                return n
            swipe(cy > SCREEN_H // 2)
            continue
        if time.time() > end:
            return None
        if scroll and nodes and scrolled < 8:
            swipe(scrolled < 4)
            scrolled += 1
        else:
            time.sleep(0.5)


def tap(rid=None, text=None, pkg=None, timeout=15, scroll=True):
    n = locate(rid, text, pkg, timeout, scroll)
    if n is None:
        raise AssertionError("not on screen: %s" % (rid or text))
    x, y = center(n)
    tap_xy(x, y)
    time.sleep(0.6)
    return x, y


def scroll_top():
    for _ in range(3):
        swipe(False)


def dialog_button(pkg, timeout=45):
    """The positive button of a dialog shown by the given package, or None."""
    end = time.time() + timeout
    while time.time() < end:
        nodes = dump()
        # An Element without children is false in a boolean context, so no "a or b" here.
        n = find(nodes, rid="button1", pkg=pkg)
        if n is None:
            n = find(nodes, rid="permission_allow_button")
        if n is not None:
            return n
        time.sleep(0.5)
    return None


# ---------------------------------------------------------------- device state

def prefs():
    xml = read_file(DATA + "/shared_prefs/job.xml")
    out = {}
    for m in re.finditer(r'<(\w+) name="(\w+)"(?: value="([^"]*)")?\s*/?>([^<]*)', xml):
        out[m.group(2)] = m.group(3) if m.group(3) is not None else m.group(4)
    return out


def job_active():
    return prefs().get("active") == "true"


def fill_kb():
    """Kilobytes held by fill files right now."""
    out = sh("du -sk %s 2>/dev/null" % WIPE_DIR).split()
    return int(out[0]) if out and out[0].isdigit() else 0


def fill_files():
    return len([l for l in sh("ls %s 2>/dev/null" % WIPE_DIR).split() if l.endswith(".bin")])


def free_kb():
    out = sh("stat -f -c '%a %S' /data").split()
    return int(out[0]) * int(out[1]) // 1024 if len(out) == 2 else -1


def pid():
    out = sh("pidof %s" % PKG).strip()
    if out.isdigit():
        return int(out)
    for line in sh("ps").splitlines() + sh("ps -A").splitlines():
        parts = line.split()
        if parts and parts[-1] == PKG and len(parts) > 1 and parts[1].isdigit():
            return int(parts[1])
    return None


def service(action):
    """Sends an action straight to the service (as root; the service is not exported)."""
    sh("am startservice -n %s -a %s%s" % (SERVICE, ACTION, action))


def wait_until(fn, timeout, what, step=1.0):
    end = time.time() + timeout
    while time.time() < end:
        if fn():
            return
        time.sleep(step)
    raise AssertionError("timed out waiting for: " + what)


def growing(seconds=4):
    a = fill_kb()
    end = time.time() + seconds
    while time.time() < end:
        time.sleep(1)
        if fill_kb() > a + 1024:
            return True
    return False


def holding_still(seconds=3):
    time.sleep(1.5)  # writes under way when the pause arrived
    a = fill_kb()
    time.sleep(seconds)
    return abs(fill_kb() - a) < 1024


def launch():
    sh("am start -n %s/.MainActivity" % PKG)
    time.sleep(2)


# ---------------------------------------------------------------- steps

def start_wipe(first=False):
    """Start button, system dialogs if they come up, confirmation."""
    scroll_top()
    tap(rid="btn_start")
    for _ in range(3):
        nodes = dump()
        if find(nodes, rid="permission_allow_button") is not None:
            if first:
                shot("notification_permission")
            x, y = center(find(nodes, rid="permission_allow_button"))
            tap_xy(x, y)
            time.sleep(1.5)
            continue
        other = find(nodes, rid="button1")
        if other is not None and other.get("package") != PKG:
            if first:
                shot("background_permission")
            tap_xy(*center(other))
            time.sleep(1.5)
            continue
        break
    confirm = dialog_button(PKG)
    if confirm is None:
        shot("no_confirm_dialog")
        raise AssertionError("the confirmation dialog did not appear")
    if first:
        shot("confirm")
    tap_xy(*center(confirm))
    wait_until(job_active, 15, "job to become active")


def wait_finished(timeout, expect):
    wait_until(lambda: not job_active(), timeout, "job to finish", step=2)
    time.sleep(1.5)
    got = prefs().get("last_result")
    assert got == expect, "result is %s, expected %s (%s)" % (got, expect, prefs().get("last_failure"))
    assert fill_files() == 0, "fill files left behind: %d" % fill_files()


def dismiss_result():
    launch()
    try:
        tap(rid="btn_dismiss", timeout=8)
    except AssertionError:
        pass


def step(name):
    def wrap(fn):
        def run():
            log("== " + name)
            t0 = time.time()
            try:
                fn()
                results.append((name, "PASS", "%.0f s" % (time.time() - t0)))
                log("   PASS")
            except Exception as e:  # noqa: BLE001
                shot("FAILED_" + re.sub(r"\W+", "_", name))
                results.append((name, "FAIL", str(e)))
                log("   FAIL: %s" % e)
                traceback.print_exc()
                # leave a clean state for the next step
                service("STOP")
                try:
                    wait_until(lambda: not job_active(), 120, "cleanup", step=2)
                except AssertionError:
                    sh("am force-stop " + PKG)
                    sh("rm -rf %s; rm -f %s/shared_prefs/job.xml %s/files/job.*" % (WIPE_DIR, DATA, DATA))
                dismiss_result()
        return run
    return wrap


@step("first launch asks for unrestricted background use")
def first_launch():
    launch()
    button = dialog_button(None, timeout=90)
    assert button is not None and button.get("package") != PKG, "the system dialog did not appear"
    shot("first_launch_background_dialog")
    tap_xy(*center(button))
    time.sleep(1.5)
    assert PKG in sh("dumpsys deviceidle whitelist"), "the app is not on the battery whitelist after Allow"
    shot("setup")
    tap(rid="btn_options")
    assert locate(rid="rb_tauto", timeout=5) is not None, "the options did not open"
    shot("options")
    scroll_top()
    tap(rid="btn_options")
    time.sleep(0.5)
    assert find(dump(), rid="rb_tauto") is None, "the options did not close"


def set_language(item, expect):
    scroll_top()
    tap(rid="btn_lang")
    time.sleep(0.5)
    if item == "简体中文":
        shot("language_menu")
    tap(text=item, scroll=False)
    time.sleep(1.5)
    assert locate(text=expect, timeout=10) is not None, "the screen did not switch to " + item
    scroll_top()


def night(on):
    sh("cmd uimode night %s" % ("yes" if on else "no"))
    time.sleep(2.5)


@step("language switch and dark theme")
def language_and_theme():
    if API >= 29:
        night(True)
        shot("setup_dark")
        night(False)
    set_language("简体中文", "开始覆写")
    shot("setup_zh")
    tap(rid="btn_options")
    time.sleep(0.5)
    shot("options_zh")
    scroll_top()
    tap(rid="btn_options")
    tap(rid="btn_help")
    time.sleep(0.8)
    shot("help_zh")
    sh("input keyevent 4")
    if API >= 29:
        night(True)
        shot("setup_zh_dark")
        night(False)
    assert locate(text="开始覆写", timeout=10) is not None, "the screen did not come back after the theme change"
    set_language("English", "Start wiping")


@step("switch to Chinese for the remaining steps")
def to_chinese():
    set_language("简体中文", "开始覆写")


@step("a complete wipe: notification, foreground service, wake lock, cleanup")
def complete_run():
    free_before = free_kb()
    start_wipe(first=True)
    time.sleep(2)
    shot("running")
    wait_until(lambda: fill_kb() > 50 * 1024, 60, "the first 50 MB")
    services = sh("dumpsys activity services " + PKG)
    assert "isForeground=true" in services, "the service is not in the foreground"
    assert "FreeSpaceWiper:fill" in sh("dumpsys power"), "no wake lock is held"
    notifications = sh("dumpsys notification --noredact")
    open(os.path.join(OUT, "notification.txt"), "w").write(notifications)
    section = notifications[notifications.find("pkg=" + PKG):][:6000]
    assert "pkg=" + PKG in notifications, "no notification from the app"
    assert "progress" in section.lower(), "the notification carries no progress"
    sh("cmd statusbar expand-notifications")
    time.sleep(2.5)
    shot("notification_shade")
    sh("cmd statusbar collapse")
    seen = set()
    end = time.time() + 900
    while job_active() and time.time() < end:
        time.sleep(3)
        if fill_kb() > 0 and len(seen) < 6:
            mark = len(seen)
            seen.add(mark)
            shot("running_%d" % mark)
    wait_finished(30, "DONE")
    launch()
    shot("result")
    free_after = free_kb()
    assert abs(free_after - free_before) < 200 * 1024, "free space not restored: %d -> %d KB" % (free_before, free_after)
    last = prefs()
    log("   overwritten: %s bytes, reserved left: %s bytes" % (last.get("last_total"), last.get("last_unreachable")))
    tap(rid="btn_dismiss")
    time.sleep(1)
    shot("setup_after_run")


@step("pause, continue and stop with the buttons")
def pause_and_stop():
    start_wipe()
    wait_until(lambda: fill_kb() > 20 * 1024, 60, "the first 20 MB")
    service("PAUSE")
    assert holding_still(), "data kept growing after Pause"
    launch()
    shot("paused")
    pause_xy = center(locate(rid="btn_pause"))
    stop_xy = center(locate(rid="btn_stop"))
    tap_xy(*pause_xy)  # Continue
    assert growing(), "nothing was written after Continue"
    time.sleep(1)
    shot("running_again")
    tap_xy(*pause_xy)  # Pause: the button must still be where it was
    assert holding_still(), "data kept growing after the Pause button"
    assert job_active(), "pausing ended the job"
    tap_xy(*stop_xy)
    confirm = dialog_button(PKG)
    assert confirm is not None, "no confirmation after Stop"
    shot("stop_confirm")
    tap_xy(*center(confirm))
    wait_finished(120, "CANCELLED")
    launch()
    shot("stopped")
    tap(rid="btn_dismiss")


@step("the process is killed: the system restarts it and the wipe continues")
def killed_and_restarted():
    start_wipe()
    wait_until(lambda: fill_kb() > 100 * 1024, 90, "the first 100 MB")
    before = fill_kb()
    victim = pid()
    assert victim, "no process"
    sh("kill -9 %d" % victim)
    time.sleep(1)
    try:
        wait_until(lambda: pid() not in (None, victim) and growing(3), 60, "automatic restart")
        log("   restarted by the system")
    except AssertionError:
        # Some Android versions do not restart a killed foreground service; the app then offers Resume.
        log("   not restarted by the system, resuming by hand")
        launch()
        shot("leftover_panel")
        tap(rid="btn_resume")
        wait_until(lambda: job_active() and growing(3), 30, "manual resume")
    assert fill_kb() >= before - 1024, "what was written before the kill was thrown away"
    launch()
    time.sleep(1)
    shot("after_kill")
    sh("cmd statusbar expand-notifications")
    time.sleep(2.5)
    shot("notification_shade_after_kill")
    sh("cmd statusbar collapse")
    wait_finished(900, "DONE")
    dismiss_result()


@step("low battery: pauses, and continues when the charger is connected")
def low_battery():
    start_wipe()
    wait_until(lambda: fill_kb() > 20 * 1024, 60, "the first 20 MB")
    sh("dumpsys battery unplug")
    sh("dumpsys battery set level 3")
    try:
        wait_until(lambda: holding_still(2), 50, "pause on low battery", step=0.5)
        launch()
        shot("paused_low_battery")
        assert job_active(), "the job ended instead of pausing"
        sh("dumpsys battery set ac 1")
        sh("dumpsys battery set status 2")
        wait_until(lambda: growing(3), 40, "continue after the charger was connected", step=0.5)
    finally:
        sh("dumpsys battery reset")
    service("STOP")
    wait_finished(120, "CANCELLED")
    dismiss_result()


@step("the phone restarts: the wipe continues by itself after boot")
def reboot_and_resume():
    start_wipe()
    wait_until(lambda: fill_kb() > 100 * 1024, 90, "the first 100 MB")
    before = fill_kb()
    adb("reboot", timeout=60)
    time.sleep(20)
    adb("wait-for-device", timeout=300)
    wait_until(lambda: sh("getprop sys.boot_completed").strip() == "1", 300, "boot", step=3)
    adb("root")
    time.sleep(3)
    adb("wait-for-device", timeout=60)
    sh("input keyevent 82")
    wait_until(lambda: pid() is not None and (growing(3) or not job_active()), 120, "automatic resume after boot")
    assert fill_kb() >= before // 2 or not job_active(), "the files from before the restart are gone"
    launch()
    time.sleep(1)
    shot("after_reboot")
    wait_finished(900, "DONE")
    launch()
    time.sleep(1)
    shot("result_after_reboot")
    dismiss_result()


def save_logs(name):
    open(os.path.join(OUT, name), "w").write(adb("logcat", "-d", "-s", "FreeSpaceWiper:*", "AndroidRuntime:E"))


def main():
    adb("root")
    time.sleep(3)
    adb("wait-for-device")
    log("API %d, screen %dx%d, /data free %d MB" % (API, SCREEN_W, SCREEN_H, free_kb() // 1024))
    sh("logcat -c")
    first_launch()
    language_and_theme()
    complete_run()  # in English
    to_chinese()
    pause_and_stop()
    killed_and_restarted()
    low_battery()
    save_logs("logcat.txt")
    open(os.path.join(OUT, "mounts.txt"), "w").write(sh("grep ' /data ' /proc/mounts") + sh("df /data"))
    reboot_and_resume()
    save_logs("logcat_after_reboot.txt")
    lines = ["%-4s  %s  (%s)" % (status, name, note) for name, status, note in results]
    summary = "API %d\n" % API + "\n".join(lines) + "\n"
    open(os.path.join(OUT, "summary.txt"), "w").write(summary)
    print(summary)
    sys.exit(1 if any(status == "FAIL" for _, status, _ in results) else 0)


if __name__ == "__main__":
    main()
