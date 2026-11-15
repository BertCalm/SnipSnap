#!/usr/bin/env python3
"""Run the opt-in HALOBAR diagnostic on a deliberately selected Android device."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import uuid

APP = "com.snipsnap.app.brassgrow"
TEST_APP = APP + ".test"
TEST_CLASS = "com.snipsnap.app.bench.HalobarRenderBenchmark"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="Explicit adb device serial")
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--install", action="store_true", help="Install/update both APKs, retaining app data")
    parser.add_argument("--runs", type=int, choices=range(1, 10), default=3)
    parser.add_argument("--allow-emulator", action="store_true", help="Functional diagnostic only, never phone timing")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    run_id = str(uuid.uuid4())
    output = args.output or Path(f"halobar-device-{run_id}.json")
    log = output.with_suffix(".instrumentation.log")
    if output.exists() or log.exists():
        parser.error("Choose new output paths; existing reports are preserved")
    output.parent.mkdir(parents=True, exist_ok=True)
    for path in (args.app_apk, args.test_apk):
        if not path.is_file():
            parser.error(f"Missing APK: {path}")
    adb = [args.adb, "-s", args.serial]

    def run(*parts, timeout=60, binary=False):
        return subprocess.run(adb + list(parts), check=True, capture_output=True,
                              text=not binary, timeout=timeout).stdout

    if run("get-state").strip() != "device":
        parser.error("The selected device must be connected and authorized")
    hardware = run("shell", "getprop", "ro.hardware").strip()
    model = run("shell", "getprop", "ro.product.model").strip()
    fingerprint = run("shell", "getprop", "ro.build.fingerprint").strip()
    qemu = run("shell", "getprop", "ro.kernel.qemu").strip()
    emulator = qemu == "1" or any(word in hardware for word in ("ranchu", "goldfish")) or \
        fingerprint.startswith("generic") or "sdk" in model.lower()
    if emulator and not args.allow_emulator:
        parser.error("Selected device appears to be an emulator; --allow-emulator labels it as functional evidence")

    apks = []
    for package, path in ((APP, args.app_apk), (TEST_APP, args.test_apk)):
        expected = sha256(path)
        if args.install:
            print(f"Installing {path.name} on {args.serial}", flush=True)
            run("install", "-r", str(path.resolve()), timeout=300)
        # Match actual installed bytes, rather than assuming an old installation
        # has this source. Pulls and hashing happen before any measurement.
        paths = run("shell", "pm", "path", package).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:/"):
            parser.error(f"Expected one installed monolithic APK for {package}; install the supplied pair")
        device_path = paths[0].removeprefix("package:")
        with tempfile.TemporaryDirectory(prefix="halobar-apk-") as folder:
            installed = Path(folder) / "installed.apk"
            run("pull", device_path, str(installed), timeout=300)
            if sha256(installed) != expected:
                parser.error(f"Installed {package} differs from {path}; use the matching APK pair")
        apks.append(dict(package=package, file=path.name, bytes=path.stat().st_size, sha256=expected))

    print("Running six voices × eight cases. The report measures rendering and does not play audio.", flush=True)
    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
        "-e", "halobarProfile", "true", "-e", "halobarRuns", str(args.runs),
        "-e", "halobarRunId", run_id, TEST_APP + "/androidx.test.runner.AndroidJUnitRunner"],
        capture_output=True, text=True, timeout=3600)
    transcript = result.stdout + result.stderr
    with log.open("x") as stream:
        stream.write(transcript)
    # am instrument can exit zero even when its test failed. Require the runner's
    # completion and the unique, newly written device report as well.
    if result.returncode or "INSTRUMENTATION_CODE: -1" not in transcript or "OK (1 test)" not in transcript:
        raise SystemExit(f"Instrumentation did not pass; inspect {log}")
    raw = run("exec-out", "run-as", APP, "cat", f"files/benchmarks/halobar-{run_id}.json")
    report = json.loads(raw)
    if report.get("runId") != run_id or report.get("status") != "completed" or len(report.get("cases", [])) != 48:
        raise SystemExit("Device report is incomplete or belongs to another run")
    expected_cases = {"FRESH", "LONG", "DRY", "RECALL", "RATE", "RETURN", "MOTION", "SPACE"}
    voices = {"VIBRAPHONE", "MARIMBA", "XYLOPHONE", "GLOCKENSPIEL", "CROTALES", "TUBULAR_BELLS"}
    if {(r["voice"], r["case"]) for r in report["cases"]} != {(v, c) for v in voices for c in expected_cases}:
        raise SystemExit("Unexpected voice/case coverage")
    if any(len(row["runs"]) != args.runs for row in report["cases"]):
        raise SystemExit("Missing measured runs")
    envelope = dict(schemaVersion=1, apks=apks, adbSerial=args.serial,
        evidenceKind="emulator-functional" if emulator or report["device"]["emulatorLikely"] else "device-render-timing",
        playbackVerified=False, mpcVerified=False, ownerListeningApproved=False,
        deviceReport=report)
    with output.open("x") as stream:
        json.dump(envelope, stream, indent=2)
        stream.write("\n")
    print(f"Saved {output} and {log}. Audio-output and MPC gates remain separate.")


if __name__ == "__main__":
    main()
