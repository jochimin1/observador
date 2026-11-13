"""List or verify the release tags and checkout branches without changing the checkout."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.PIPE).decode("utf-8").strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail on missing or mismatched references")
    parser.add_argument("--remote", action="store_true", help="Also verify the references on origin")
    args = parser.parse_args()
    entries = json.loads((ROOT / "docs/releases/versiones.json").read_text(encoding="utf-8"))
    versions = [entry["version"] for entry in entries]
    assert len(set(versions)) == len(versions), "Duplicate catalog version"
    codes = [entry["versionCode"] for entry in entries]
    assert len(set(codes)) == len(codes), "Duplicate versionCode"
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8-sig")
    declared = set(re.findall(r"^## \[(\d+\.\d+\.\d+)\]", changelog, re.M))
    assert declared == set(versions), "Catalog and changelog do not cover the same releases"
    remote = {}
    if args.remote:
        for line in git("ls-remote", "--heads", "--tags", "origin").splitlines():
            sha, ref = line.split()
            remote[ref] = sha
    failures = []
    for entry in entries:
        version = entry["version"]
        tag = "refs/tags/v" + version
        branch = "refs/heads/release/" + version
        try:
            commit = git("rev-parse", "--verify", tag + "^{commit}")
            assert git("rev-parse", "--verify", branch) == commit, "Tag and branch differ"
            source = git("show", commit + ":app/build.gradle.kts")
            name = re.search(r'versionName\s*=\s*"([^"]+)"', source).group(1)
            code = int(re.search(r"versionCode\s*=\s*(\d+)", source).group(1))
            normalized = "1.0.0" if name == "1.0" else name
            assert normalized == version and code == entry["versionCode"], "App identity differs"
            if args.remote:
                assert remote.get(branch) == commit, "Remote branch differs or is missing"
                assert remote.get(tag + "^{}", remote.get(tag)) == commit, "Remote tag differs or is missing"
            print(f"{version:8} code={code:2} {commit[:12]}  v{version}  release/{version}")
        except (AssertionError, subprocess.CalledProcessError, AttributeError) as exc:
            failures.append(f"{version}: {exc}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Verified {len(entries)} releases" + (" locally and on origin" if args.remote else " locally"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
