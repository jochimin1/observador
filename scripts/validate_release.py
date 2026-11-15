"""Repeat the local release gates; instrumentation only with an explicit emulator serial."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

from release_tools import ROOT, source_checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--serial", help="Optional emulator-N; never a physical phone")
    parser.add_argument("--output", type=Path, default=ROOT / "build/release-validation/local")
    args = parser.parse_args()
    if args.serial and (not args.serial.startswith("emulator-") or not args.serial[9:].isdigit()):
        parser.error("Solo se permite un serial emulator-N")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    result_file = output / "results.json"
    result = {"status": "RUNNING", "startedAtUtc": datetime.now(timezone.utc).isoformat()}
    result_file.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    result.update(source_checks())

    def stage(name, command):
        print(name, flush=True)
        with (output / (name + ".log")).open("w", encoding="utf-8") as log:
            process = subprocess.run([str(x) for x in command], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        if process.returncode:
            result.update(status="FAILED", failedStage=name)
            result_file.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            raise RuntimeError(f"Falló {name}: revisar {output / (name + '.log')}")

    stage("python-tests", [sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"])
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    gradle = [wrapper] if os.name == "nt" else ["bash", wrapper]
    stage("gradle", gradle + ["testDebugUnitTest", "assembleDebug", "assembleDebugAndroidTest", "assembleRelease",
                              "lintDebug", "lintRelease", "--build-cache", "--max-workers=2", "--console=plain"])
    check = [sys.executable, "scripts/check_release.py", "--apk", "app/build/outputs/apk/debug/app-debug.apk",
             "--output", output / "apk.json"]
    if args.previous_apk:
        check += ["--previous-apk", args.previous_apk.resolve()]
    stage("apk", check)
    reports = list((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if not reports:
        raise RuntimeError("No hay resultados JVM")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    if not totals["tests"] or totals["failures"] or totals["errors"] or totals["skipped"]:
        raise RuntimeError("Suite JVM incompleta o fallida")
    result["jvm"] = totals
    if args.serial:
        command = [sys.executable, "scripts/test_emulator.py", "--serial", args.serial, "--output", output / "emulator"]
        if args.previous_apk:
            command += ["--previous-apk", args.previous_apk.resolve()]
        stage("instrumentation", command)
        result["instrumentation"] = json.loads((output / "emulator/results.json").read_text(encoding="utf-8"))
    else:
        result["instrumentation"] = "NOT_RUN: pass --serial emulator-N"
    result["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
    result["status"] = "PASSED" if args.serial else "HOST_PASSED"
    result_file.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as exc:
        sys.exit(str(exc))
