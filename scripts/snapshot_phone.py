"""Read-only, stable DB/WAL backup before or after an adb install -r. Never stops the app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("serial")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    package = "com.ubers.observador"

    def call(*parts):
        return subprocess.check_output([str(adb), "-s", args.serial, *parts], stderr=subprocess.PIPE)

    def signature():
        names = call("exec-out", "run-as", package, "ls", "databases").decode().split()
        files = ["databases/observador.db"]
        if "observador.db-wal" in names:
            files.append("databases/observador.db-wal")
        raw = call("exec-out", "run-as", package, "sha256sum", *files)
        return {line.split()[1].decode().split("/")[-1]: line.split()[0].decode() for line in raw.splitlines()}

    args.destination.mkdir(parents=True, exist_ok=False)
    before = signature()
    for name in before:
        if name not in ("observador.db", "observador.db-wal"):
            raise RuntimeError("Unexpected database filename")
        data = call("exec-out", "run-as", package, "cat", "databases/" + name)
        if hashlib.sha256(data).hexdigest() != before[name]:
            raise RuntimeError("La base cambió durante la copia. Repite con otra carpeta antes de instalar.")
        (args.destination / name).write_bytes(data)
    if signature() != before:
        raise RuntimeError("La base cambió durante la copia. Repite con otra carpeta antes de instalar.")
    with sqlite3.connect(args.destination / "observador.db") as db:
        if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RuntimeError("La copia no pasó quick_check. No instalar.")
        tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        counts = {name: db.execute('SELECT COUNT(*) FROM "' + name.replace('"', '""') + '"').fetchone()[0] for name in tables}
        active = db.execute("SELECT COUNT(*) FROM trips WHERE status IN ('TO_PICKUP','ARRIVED_OR_WAITING','IN_TRIP','UNKNOWN')").fetchone()[0]
        result = {"schema": db.execute("PRAGMA user_version").fetchone()[0], "counts": counts, "openTrips": active, "integrity": "ok"}
    (args.destination / "counts.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if active:
        raise SystemExit("Hay viajes abiertos. La copia existe, pero espera a terminar el viaje antes de instalar.")


if __name__ == "__main__":
    main()
