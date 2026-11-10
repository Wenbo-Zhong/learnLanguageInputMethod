#!/usr/bin/env python3
"""Create the dedicated test simulator; optionally wait for an existing runtime download."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--wait", action="store_true", help="Wait up to two hours for the running Xcode runtime download")
parser.add_argument("--test", action="store_true", help="Run the compiled real-keyboard UI tests after installing")
parser.add_argument("--derived-data", default="/tmp/learnime-ios-simulator-build")
args = parser.parse_args()
env = os.environ.copy()
env.setdefault("DEVELOPER_DIR", "/Applications/Xcode_27.0_beta6.app/Contents/Developer")
env.setdefault("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")

def run(*command):
    return subprocess.check_output(command, env=env, text=True).strip()

deadline = time.monotonic() + (7200 if args.wait else 0)
if args.wait:
    print("Waiting for the existing iOS runtime download; no second download will be started.", flush=True)
while True:
    runtimes = json.loads(run("xcrun", "simctl", "list", "runtimes", "-j"))["runtimes"]
    available = [r for r in runtimes if r.get("isAvailable") and r["name"].startswith("iOS")]
    if available:
        runtime = max(available, key=lambda r: tuple(map(int, r["version"].split("."))))
        break
    if time.monotonic() >= deadline:
        raise SystemExit("No available iOS runtime. Finish xcodebuild -downloadPlatform iOS first.")
    time.sleep(30)

name = "LearnIME Test iPhone"
devices = json.loads(run("xcrun", "simctl", "list", "devices", "-j"))["devices"].get(runtime["identifier"], [])
device = next((d for d in devices if d["name"] == name and d.get("isAvailable")), None)
udid = device["udid"] if device else run("xcrun", "simctl", "create", name,
    "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro", runtime["identifier"])
print(f"SIMULATOR_CREATED name={name} udid={udid} runtime={runtime['identifier']}", flush=True)
if not device or device["state"] != "Booted":
    run("xcrun", "simctl", "boot", udid)
run("xcrun", "simctl", "bootstatus", udid, "-b")
# Test automation uses English system labels. Product content remains Chinese.
run("xcrun", "simctl", "spawn", udid, "defaults", "write", "NSGlobalDomain", "AppleLanguages", "-array", "en")
run("xcrun", "simctl", "spawn", udid, "defaults", "write", "NSGlobalDomain", "AppleLocale", "en_US")
run("xcrun", "simctl", "status_bar", udid, "override", "--time", "9:41", "--batteryState", "charged", "--batteryLevel", "100")
app = Path(args.derived_data) / "Build/Products/Debug-iphonesimulator/LearnIME.app"
if not app.exists():
    raise SystemExit(f"Build the simulator app first: missing {app}")
run("xcrun", "simctl", "install", udid, str(app))
run("xcrun", "simctl", "launch", udid, "dev.learnime.app")
simulator = Path(env["DEVELOPER_DIR"]) / "Applications/Simulator.app"
print("SIMULATOR_INSTALLED dev.learnime.app", flush=True)
if simulator.exists():
    subprocess.run(["open", "-a", str(simulator), "--args", "-CurrentDeviceUDID", udid], check=True)
else:
    # Xcode 27 integrates simulators into Device Hub instead of shipping Simulator.app.
    hub = Path(env["DEVELOPER_DIR"]).parent / "Applications/DeviceHub.app"
    if hub.exists():
        subprocess.run(["open", "-a", str(hub)], check=True)
if args.test:
    subprocess.run(["xcodebuild", "-project", str(ROOT / "iosApp/LearnIME.xcodeproj"),
        "-scheme", "LearnIME", "-configuration", "Debug", "-destination", f"platform=iOS Simulator,id={udid}",
        "-derivedDataPath", args.derived_data, "-resultBundlePath", f"/tmp/learnime-simulator-{int(time.time())}.xcresult",
        "-parallel-testing-enabled", "NO", "CODE_SIGNING_ALLOWED=NO", "test-without-building"], env=env, check=True)
    print("SIMULATOR_UI_TESTS_PASSED", flush=True)
