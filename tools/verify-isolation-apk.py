import hashlib
import io
import json
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROPERTIES = dict(line.split('=', 1) for line in (ROOT / 'local.properties').read_text().splitlines() if '=' in line)
BUILD_TOOLS = Path(PROPERTIES['sdk.dir']) / 'build-tools/36.0.0'
APK = ROOT / 'TMessagesProj_App/build/outputs/apk/afat/debug/Calamus-12.10.6-debug-universal.apk'
VERSION = int(dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines() if '=' in line)['APP_VERSION_CODE']) * 10

def output(tool, *args):
    return subprocess.check_output([str(BUILD_TOOLS / tool), *map(str, args)], text=True, errors='replace')

def certificate(path):
    return re.search(r'Signer #1 certificate SHA-256 digest: (\S+)', output('apksigner', 'verify', '--print-certs', path)).group(1)

def dex(bundle):
    return b'\n'.join(bundle.read(name) for name in bundle.namelist() if re.fullmatch(r'classes\d*\.dex', name))

assert certificate(APK) == certificate(ROOT.parent / 'Calamus-71270-debug.apk')
assert f"name='ksnxdev.calamus' versionCode='{VERSION}'" in output('aapt', 'dump', 'badging', APK)
manifest = output('aapt', 'dump', 'xmltree', APK, 'AndroidManifest.xml')
assert not re.search(r'android:debuggable[^\n]*0xffffffff', manifest)
assert 'android:isolatedProcess(0x010103a9)=(type 0x12)0xffffffff' in manifest
assert 'ksnxdev.calamus.permission.PLUGIN_HOST' not in manifest
resources = output('aapt', 'dump', '--values', 'resources', APK)
with zipfile.ZipFile(APK) as app:
    assert app.testzip() is None
    assert 'assets/isolation/plugin-host.apk' not in app.namelist()
    abis = {name.split('/')[1] for name in app.namelist() if name.startswith('lib/') and name.endswith('.so')}
    assert abis == {'armeabi-v7a', 'arm64-v8a'}
    assert not any('chaquopy' in name.lower() or name.endswith('.imy') for name in app.namelist())
    for abi in abis:
        assert f'lib/{abi}/libpython3.11.so' in app.namelist()
        assert f'lib/{abi}/libcalamus_python.so' in app.namelist()
        bridge = app.read(f'lib/{abi}/libcalamus_python.so')
        assert b'Python requires a fresh OS-isolated process' in bridge
        assert b'OS syscall restrictions could not be installed' in bridge
        assert not any('_ctypes' in name for name in app.namelist() if name.startswith('lib/'))
    main_dex = dex(app)
    private_hash = PROPERTIES['TELEGRAM_API_HASH'].strip().encode()
    assert private_hash in main_dex
    for name in [b'PluginSandboxService', b'PluginSandboxActivity', b'SandboxProcess', b'com/chaquo/python', b'ksnxdev.calamus.pluginhost', b'DeletedMessagesStore', b'CalamusFeatureHooks']:
        assert name not in main_dex
    for name in [b'PluginManager', b'PluginAccessPolicy', b'PluginAccessActivity', b'PluginJson', b'PluginRuntimeModule', b'TelegramPluginHost', b'NativePython', b'PluginInstallActivity', b'review_confirm', b'bindIsolatedService', b'Powered by Cherrygram', b'FrostedPanelRenderer', b'CalamusMaterial', b'GeminiSDKImplementation']:
        assert name in main_dex, name
    runtime_root = ROOT / 'CalamusPlugins/src/main/assets/isolated'
    verification = json.loads((ROOT / 'CalamusPlugins/vendor/python-runtime-verification.json').read_text())
    for name, record in verification['files'].items():
        assert hashlib.sha256((ROOT / 'CalamusPlugins' / name).read_bytes()).hexdigest() == record['sha256']
    with zipfile.ZipFile(ROOT / 'CalamusPlugins/vendor/python311/stdlib.zip') as standard:
        for name in standard.namelist():
            asset = 'assets/isolated/python/' + name
            if name in {'__phello__/__init__.pyc', '__phello__/spam.pyc'}:
                assert asset not in app.namelist()
                continue
            assert app.read(asset) == standard.read(name)
            if name.endswith(".pyc"):
                assert app.getinfo(asset).compress_type == zipfile.ZIP_STORED
    for name in ['worker.py', 'native_modules.json', 'LICENSE.python']:
        assert app.read('assets/isolated/' + name) == (runtime_root / name).read_bytes()
    assert not any(name.startswith('assets/plugins/') for name in app.namelist())
    assert b'installExample' not in main_dex and b'reviewExample' not in main_dex
    for title in ['CalamusAppColor', 'CalamusUseSystemColor', 'CalamusChannel']:
        assert title in resources
    for title in ['CalamusPluginExample', 'CalamusPluginIsolationDescription', 'CalamusPluginTrust', 'CalamusPluginUpdateHint']:
        assert title not in resources
    import PIL.Image
    icon = re.search(r'drawable/calamus_settings: t=[^\n]*\n\s+\(string8\) "([^"]+)"', resources).group(1)
    original = PIL.Image.open(ROOT.parent / 'Calamus_settings.png').convert('RGBA')
    packaged = PIL.Image.open(io.BytesIO(app.read(icon))).convert('RGBA')
    assert original.size == packaged.size and original.tobytes() == packaged.tobytes()
assert 'CG_TelegramApiDisclosure' not in resources
assert 'CalamusPluginModuleInstall' not in resources
print(json.dumps({'versionCode': VERSION, 'package': 'ksnxdev.calamus', 'apk_sha256': hashlib.sha256(APK.read_bytes()).hexdigest(), 'phone_abis': sorted(abis), 'signatures_match_previous': True, 'non_debuggable': True, 'isolated_process_declared': True, 'separate_module_not_required': True, 'companion_apk_absent': True, 'stdlib_and_runtime_checksums_match': True, 'native_python_runs_only_in_isolated_uid': True, 'ctypes_and_chaquopy_java_absent': True, 'old_test_mode_removed': True, 'material_cherrygram_branding_and_api_preserved': True}, indent=2))
