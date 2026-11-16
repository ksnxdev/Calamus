#!/usr/bin/env python3
import argparse
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
from plugin_package import unpack

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
if args.output.suffix.lower() != ".plug":
    parser.error("Plugin output must have the .plug extension")
with tempfile.TemporaryDirectory() as temporary:
    archive = Path(temporary) / "plugin.plug"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        for file in sorted(args.source.rglob("*")):
            if file.is_file() and "__pycache__" not in file.parts and not file.name.startswith("."):
                if file.is_symlink():
                    raise ValueError("Symbolic links are unsupported")
                entry = zipfile.ZipInfo(file.relative_to(args.source).as_posix(), (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                bundle.writestr(entry, file.read_bytes())
    destination = Path(temporary) / "unpacked"
    destination.mkdir()
    info = unpack(archive, destination)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(archive.read_bytes())
print(info["id"], info["version"], "->", args.output)
