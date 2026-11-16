#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
props = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines() if '=' in line)
version = props['CALAMUS_VERSION_NAME']
prefix = 'Calamus-' + version + '-source'
excluded_names = {'local.properties', 'calamusapi.txt', 'calamus-signing.properties', 'secrets_for_ci.env', 'AGENTS.md', 'google-services.json', 'agconnect-services.json', 'CALAMUS.md', 'CALAMUS_BUG_AUDIT.md', 'RELEASE_READINESS.md'}
excluded_suffixes = {'.jks', '.keystore', '.p12', '.apk', '.aab', '.log', '.hprof', '.pyc'}

def git(*arguments):
    return subprocess.check_output(['git', '-C', str(ROOT), *arguments])

paths = {Path(value.decode()) for value in (git('ls-files', '--recurse-submodules', '-z') + git('ls-files', '--others', '--exclude-standard', '-z')).split(b'\0') if value}
selected = []
for relative in sorted(paths):
    if relative.name in excluded_names or relative.suffix in excluded_suffixes or relative.name.startswith('.env'):
        continue
    if any(part in {'.git', '.gradle', '.cxx', '__pycache__', '.idea', '.github'} for part in relative.parts):
        continue
    path = ROOT / relative
    if not path.is_file():
        continue
    if path.is_symlink() and not path.resolve().is_relative_to(ROOT):
        raise SystemExit('Source symlink escapes the project: ' + str(relative))
    selected.append(relative)

secrets = []
local = ROOT / 'local.properties'
if local.exists():
    for line in local.read_text().splitlines():
        if line.startswith(('TELEGRAM_API_HASH=', 'GOOGLE_MAPS_API_KEY=')):
            value = line.split('=', 1)[1].strip().strip('"\'')
            if len(value) >= 16:secrets.append(value.encode())
signing_path = Path(os.environ.get('CALAMUS_SIGNING_PROPERTIES', str(ROOT.parent / 'signing/calamus-signing.properties')))
if signing_path.exists():
    for line in signing_path.read_text().splitlines():
        if line.startswith(('CALAMUS_KEYSTORE_PASSWORD=', 'CALAMUS_KEY_PASSWORD=')):
            value = line.split('=', 1)[1]
            if len(value) >= 16:secrets.append(value.encode())

args.output.parent.mkdir(parents=True, exist_ok=True)
temporary = args.output.with_suffix(args.output.suffix + '.partial')
manifest = {}

def metadata(info):
    info.uid = info.gid = 0
    info.uname = info.gname = ''
    return info

def add_text(bundle, name, text):
    data = text.encode()
    info = tarfile.TarInfo(prefix + '/' + name)
    info.size = len(data)
    info.mode = 0o644
    bundle.addfile(info, io.BytesIO(data))

try:
    with tarfile.open(temporary, 'w:gz', compresslevel=5) as bundle:
        for relative in selected:
            path = ROOT / relative
            data = path.read_bytes()
            if any(secret in data for secret in secrets):
                raise RuntimeError('Sensitive value detected in source file: ' + str(relative))
            if relative.suffix in {'.java', '.kt', '.json', '.xml', '.properties', '.gradle', '.py', '.md', '.txt', '.yml', '.yaml', '.sh'}:
                for pattern in [rb'AIza[0-9A-Za-z_-]{35}', rb'\b[0-9]{8,12}:[A-Za-z0-9_-]{35}\b', rb'\bgh[pousr]_[A-Za-z0-9]{36,255}\b', rb'\bgithub_pat_[A-Za-z0-9_]{40,255}\b']:
                    if re.search(pattern, data):raise RuntimeError('Credential-like value detected in source file: ' + str(relative))
            manifest[str(relative)] = hashlib.sha256(data).hexdigest()
            bundle.add(path, arcname=prefix + '/' + str(relative), recursive=False, filter=metadata)
        add_text(bundle, 'SOURCE_REVISION.txt', git('rev-parse', 'HEAD').decode().strip() + '\n')
        add_text(bundle, 'SOURCE_SUBMODULES.txt', git('submodule', 'status').decode())
        add_text(bundle, 'SOURCE_MANIFEST.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(args.output)
except BaseException:
    temporary.unlink(missing_ok=True)
    raise

with args.output.open('rb') as stream:digest = hashlib.file_digest(stream, 'sha256').hexdigest()
print(json.dumps({'archive': str(args.output), 'version': version, 'source_files': len(selected), 'sha256': digest, 'bytes': args.output.stat().st_size, 'known_credentials_scan_passed': True, 'submodule_sources_included': True}, ensure_ascii=False))
