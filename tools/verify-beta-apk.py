#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--emulator', action='store_true')
parser.add_argument('--mapping', type=Path, default=ROOT / 'TMessagesProj_App/build/outputs/mapping/afatStandalone/mapping.txt')
args = parser.parse_args()
local = dict(line.split('=', 1) for line in (ROOT / 'local.properties').read_text().splitlines() if '=' in line)
properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines() if '=' in line)
tools = Path(local['sdk.dir']) / 'build-tools/36.0.0'

def output(name, *arguments):
    return subprocess.check_output([str(tools / name), *map(str, arguments)], text=True)

badging = output('aapt', 'dump', 'badging', args.apk)
manifest = output('aapt', 'dump', 'xmltree', args.apk, 'AndroidManifest.xml')
signer = output('apksigner', 'verify', '--verbose', '--print-certs', args.apk)
assert f"name='ksnxdev.calamus' versionCode='{properties['APP_VERSION_CODE']}' versionName='{properties['CALAMUS_VERSION_NAME']}'" in badging
assert "application-label:'Calamus'" in badging
assert not re.search(r'android:(?:debuggable|testOnly)[^\n]*0xffffffff', manifest)
assert 'android:isolatedProcess(0x010103a9)=(type 0x12)0xffffffff' in manifest
assert 'CN=Calamus' in signer and 'CN=Android Debug' not in signer
assert args.mapping.exists() and args.mapping.stat().st_size > 1000
config = ROOT / 'TMessagesProj/build/generated/source/buildConfig/standalone/org/telegram/messenger/BuildConfig.java'
configuration = config.read_text()
assert 'boolean DEBUG_VERSION = false;' in configuration
assert 'boolean CALAMUS_API_CONFIGURED = true;' in configuration
api_hash = re.search(r'String CALAMUS_API_HASH = "([0-9a-fA-F]{32})";', configuration).group(1)

def aligned_elf(data):
    assert data[:4] == b'\x7fELF' and data[4] == 2 and data[5] == 1
    table = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    return all(struct.unpack_from('<Q', data, table + size * index + 48)[0] >= 16384 for index in range(count) if struct.unpack_from('<I', data, table + size * index)[0] == 1)

source_icon = ROOT / 'TMessagesProj/src/main/res-cherrygram/drawable-nodpi/calamus_icon_art.jpg'
icon_sha = hashlib.sha256(source_icon.read_bytes()).hexdigest()
expected_abis = {'x86_64'} if args.emulator else {'arm64-v8a', 'armeabi-v7a'}
runtime = json.loads((ROOT / 'CalamusPlugins/vendor/python-runtime-verification.json').read_text())
libraries = 0
with zipfile.ZipFile(args.apk) as bundle:
    names = bundle.namelist()
    assert {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')} == expected_abis
    assert not any(name.endswith('.apk') or name.endswith('.plug') for name in names)
    dex = b'\n'.join(bundle.read(name) for name in names if re.fullmatch(r'classes\d*\.dex', name))
    assert api_hash.encode() in dex
    assert b'https://github.com/ksnxdev/Calamus' in dex
    assert 'Calamus' in bundle.read('assets/calamus_privacy.txt').decode()
    assert any(hashlib.sha256(bundle.read(name)).hexdigest() == icon_sha for name in names if name.lower().endswith(('.jpg', '.jpeg')))
    for name in names:
        assert str(Path.home()).encode() not in bundle.read(name), 'Local build path: ' + name
        if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
            assert aligned_elf(bundle.read(name)), 'ELF alignment: ' + name
            libraries += 1
    for relative, details in runtime['files'].items():
        if '/jniLibs/' not in relative:continue
        abi, filename = relative.split('/jniLibs/', 1)[1].split('/', 1)
        if abi not in expected_abis:continue
        packaged = 'lib/' + abi + '/' + filename
        assert packaged in names and hashlib.sha256(bundle.read(packaged)).hexdigest() == details['sha256'], 'Runtime mismatch: ' + filename
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(args.apk)], check=True, stdout=subprocess.DEVNULL)
with args.apk.open('rb') as stream:digest = hashlib.file_digest(stream, 'sha256').hexdigest()
print(json.dumps({'package': 'ksnxdev.calamus', 'versionName': properties['CALAMUS_VERSION_NAME'], 'versionCode': int(properties['APP_VERSION_CODE']), 'apk_sha256': digest, 'bytes': args.apk.stat().st_size, 'abis': sorted(expected_abis), 'debuggable': False, 'test_only': False, 'build_config_debug_version': False, 'telegram_api_configured': True, 'signer_sha256': re.search(r'Signer #1 certificate SHA-256 digest: (\S+)', signer).group(1), 'new_permanent_signature': True, 'r8_mapping_present': True, 'isolated_process': True, 'python_runtime_checksums_match': True, 'provided_icon_sha256': icon_sha, 'github_source_link': 'https://github.com/ksnxdev/Calamus', 'zip_alignment_16kb': True, 'arm64_libraries_16kb_alignment': libraries, 'executed_on_16kb_device': False}, ensure_ascii=False, indent=2))
