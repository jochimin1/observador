"""Run release regressions on an explicitly named emulator. Never accepts a physical device."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

from release_tools import PACKAGE, ROOT, check_update, inspect_apk, instrumentation_count, run, sdk_tool


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "build/release-validation/emulator")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Solo se permite un serial emulator-N; nunca el teléfono del conductor")
    adb = sdk_tool("adb")

    def device(*parts):
        return run([adb, "-s", args.serial, *parts])

    if device("shell", "getprop", "ro.kernel.qemu") != "1":
        raise ValueError("El dispositivo no acredita ser un emulador")
    args.output.mkdir(parents=True, exist_ok=True)
    current = inspect_apk(args.apk)
    test = inspect_apk(args.test_apk)
    if current["package"] != PACKAGE or not current["debuggable"]:
        raise ValueError("La instrumentación requiere el APK debug de Observador")
    if test["package"] != PACKAGE + ".test" or test["certificatesSha256"] != current["certificatesSha256"]:
        raise ValueError("El APK de pruebas no corresponde a esta firma/paquete")
    previous = inspect_apk(args.previous_apk) if args.previous_apk else None
    if previous:
        if not previous["debuggable"]:
            raise ValueError("La base de instrumentación debe ser debug")
        check_update(previous, current)

    def instrument(classes, label):
        output = device("shell", "am", "instrument", "-w", "-r", "-e", "class", classes,
                        PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        (args.output / (label + ".txt")).write_text(output, encoding="utf-8")
        count = instrumentation_count(output)
        print(f"{label}: OK ({count} tests)", flush=True)
        return count

    counts = {}
    if previous:
        print("Instalando base anterior exclusivamente en el emulador", flush=True)
        # Only the disposable emulator may return to the previous debug baseline on a rerun.
        device("install", "-r", "-d", str(args.previous_apk.resolve()))
        device("install", "-r", str(args.test_apk.resolve()))
        counts["upgradeSeed"] = instrument(PACKAGE + ".ReleaseUpgradeTest#seed", "upgrade-seed")
    device("install", "-r", str(args.apk.resolve()))
    device("install", "-r", str(args.test_apk.resolve()))
    if previous:
        counts["upgradeVerify"] = instrument(PACKAGE + ".ReleaseUpgradeTest#verify", "upgrade-verify")
    migrations = sorted(path.stem for path in (ROOT / "app/src/androidTest/java/com/ubers/observador").glob("Migration*Test.kt"))
    classes = migrations + ["ReleaseMigrationTest", "StartupSettingsTest", "OperatingRepositoryTest",
                            "ClosureRepositoryTest", "BackupRoundTripTest", "FullDataTest"]
    counts["regressions"] = instrument(",".join(PACKAGE + "." + name for name in classes), "regressions")
    result = {"serial": args.serial, "api": device("shell", "getprop", "ro.build.version.sdk"),
              "model": device("shell", "getprop", "ro.product.model"), "counts": counts,
              "apk": current, "testApk": test, "previousApk": previous, "regressionClasses": classes}
    (args.output / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as exc:
        sys.exit(str(exc))
