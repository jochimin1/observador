"""Export the previous tagged release to a NEW directory for an isolated baseline build."""
import argparse
import io
from pathlib import Path
import subprocess
import zipfile

from release_tools import ROOT, source_checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    tag = source_checks()["previousTag"]
    # git archive includes only tracked source, never phone copies, local SDK paths or keys.
    archive = subprocess.check_output(["git", "archive", "--format=zip", tag], cwd=ROOT)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(io.BytesIO(archive)) as content:
        for name in content.namelist():
            if not (output / name).resolve().is_relative_to(output):
                raise ValueError("Ruta inesperada en git archive")
        content.extractall(output)
    print(f"{tag} -> {output}")


if __name__ == "__main__":
    main()
