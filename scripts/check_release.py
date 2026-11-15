"""Validate source and optionally a signed APK and its compatibility with a previous APK."""
import argparse
import json
from pathlib import Path
import sys

from release_tools import PACKAGE, ROOT, check_update, inspect_apk, run, source_checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--require-release", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.previous_apk and not args.apk:
        parser.error("--previous-apk requiere --apk")
    if args.require_release and not args.apk:
        parser.error("--require-release requiere --apk")
    result = source_checks()
    result["sourceCommit"] = run(["git", "rev-parse", "HEAD"])
    result["trackedChanges"] = bool(run(["git", "status", "--porcelain", "--untracked-files=no"]))
    if args.apk:
        artifact = inspect_apk(args.apk)
        if artifact["package"] != PACKAGE or any(artifact[k] != result[k] for k in ("versionName", "versionCode")):
            raise ValueError("El APK no corresponde al código actual")
        if args.require_release and artifact["debuggable"]:
            raise ValueError("Un APK depurable no es una entrega release")
        result["apk"] = artifact
        if args.previous_apk:
            previous = inspect_apk(args.previous_apk)
            check_update(previous, artifact)
            result["previousApk"] = previous
            result["directUpdateCompatible"] = True
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError) as exc:
        sys.exit(str(exc))
