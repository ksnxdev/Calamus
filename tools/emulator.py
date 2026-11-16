#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT.parent / 'emulator'
AVD = 'Calamus_API35'
SERIAL = 'emulator-5580'
PACKAGE = 'ksnxdev.calamus'


def sdk_path():
    properties = ROOT / 'local.properties'
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith('sdk.dir='):
                return Path(line.split('=', 1)[1].strip())
    for candidate in [os.environ.get('ANDROID_HOME', ''), str(Path.home() / 'Android/Sdk')]:
        if candidate and (Path(candidate) / 'platform-tools/adb').exists():
            return Path(candidate)
    raise SystemExit('Android SDK not found; set sdk.dir in local.properties')


SDK = sdk_path()
ENV = dict(os.environ, ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK))
ADB = str(SDK / 'platform-tools/adb')
EMULATOR = str(SDK / 'emulator/emulator')


def adb(*args, check=True):
    return subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, check=check, timeout=30)


def booted():
    result = adb('shell', 'getprop', 'sys.boot_completed', check=False)
    return result.returncode == 0 and result.stdout.strip() == b'1'


def setup():
    avds = subprocess.check_output([EMULATOR, '-list-avds'], env=ENV, text=True).splitlines()
    if AVD not in avds:
        manager = SDK / 'cmdline-tools/latest/bin/avdmanager'
        subprocess.run([str(manager), 'create', 'avd', '-n', AVD, '-k',
                        'system-images;android-35;default;x86_64', '-d', 'pixel_6'],
                       input='no\n', text=True, check=True, env=ENV)
        avd_root = Path(os.environ.get('ANDROID_AVD_HOME', Path.home() / '.android/avd'))
        config = avd_root / f'{AVD}.avd/config.ini'
        import re
        text = config.read_text()
        values = {'hw.ramSize': '1536', 'hw.cpu.ncore': '2', 'hw.lcd.width': '720',
                  'hw.lcd.height': '1600', 'hw.lcd.density': '320',
                  'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'software',
                  'disk.dataPartition.size': '4G', 'showDeviceFrame': 'no'}
        for key, value in values.items():
            pattern = r'^' + re.escape(key) + r'\s*=.*$'
            if re.search(pattern, text, re.M):
                text = re.sub(pattern, key + ' = ' + value, text, flags=re.M)
            else:
                text += '\n' + key + ' = ' + value + '\n'
        config.write_text(text)
    print(f'AVD ready: {AVD}', flush=True)


def start(window=False):
    setup()
    devices = subprocess.check_output([ADB, 'devices'], text=True)
    if SERIAL in devices:
        name = adb('emu', 'avd', 'name', check=False).stdout.decode(errors='replace').splitlines()
        if name and name[0].strip() != AVD:
            raise SystemExit(f'{SERIAL} belongs to another AVD; leave it untouched and free port 5580')
    if SERIAL not in devices:
        STATE.mkdir(parents=True, exist_ok=True)
        command = [EMULATOR, '-avd', AVD, '-port', '5580', '-no-audio', '-no-boot-anim',
                   '-no-snapshot', '-gpu', 'software', '-memory', '1536', '-cores', '2']
        if not window:
            command.append('-no-window')
        with (STATE / 'android-emulator.log').open('w') as log:
            process = subprocess.Popen(command, env=ENV, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        print(f'Emulator process: {process.pid}', flush=True)
    deadline = time.monotonic() + 180
    while not booted():
        if time.monotonic() >= deadline:
            raise SystemExit(f'Emulator boot timed out; inspect {STATE / "android-emulator.log"}')
        time.sleep(1)
    adb('shell', 'input', 'keyevent', '82', check=False)
    print(f'Android ready: {SERIAL}', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['setup', 'start', 'status', 'install', 'screenshot', 'stop'])
    parser.add_argument('--window', action='store_true')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.command == 'setup':
        setup()
    elif args.command == 'start':
        start(args.window)
    elif args.command == 'status':
        print(subprocess.check_output([ADB, 'devices', '-l'], text=True), end='')
        print('Calamus emulator booted:', booted())
    elif args.command == 'stop':
        result = adb('emu', 'kill', check=False)
        print(result.stdout.decode(errors='replace'), end='')
    elif args.command == 'install':
        start(args.window)
        apk = args.apk
        if apk is None:
            apks = list((ROOT / 'TMessagesProj_App/build/outputs/apk/afat/debug').glob('Calamus-Emulator-*-debug-universal.apk'))
            if not apks:
                raise SystemExit('Build emulator APK first: ./tools/build-emulator.sh')
            apk = max(apks, key=lambda path: path.stat().st_mtime)
        result = adb('install', '-r', '-t', str(apk.resolve()))
        print(result.stdout.decode(errors='replace'), end='')
        result = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/org.telegram.ui.LaunchActivity')
        print(result.stdout.decode(errors='replace'), end='')
    elif args.command == 'screenshot':
        if not booted():
            raise SystemExit('Start emulator first: ./tools/emulator.py start')
        output = args.output or STATE / 'screenshots' / (time.strftime('%Y%m%d-%H%M%S') + '.png')
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(adb('exec-out', 'screencap', '-p').stdout)
        print(output.resolve())


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        if error.stderr:
            sys.stderr.write(error.stderr.decode(errors='replace') if isinstance(error.stderr, bytes) else error.stderr)
        raise SystemExit(error.returncode)
