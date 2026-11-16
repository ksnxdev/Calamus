#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
python3 - <<'PY'
from pathlib import Path
import os,subprocess
props=dict(line.split('=',1) for line in Path('local.properties').read_text().splitlines() if '=' in line)
adb=[str(Path(props['sdk.dir'])/'platform-tools/adb'),'-s','emulator-5580']
assert subprocess.check_output(adb+['emu','avd','name'],text=True).splitlines()[0]=='Calamus_API35'
previous=subprocess.check_output(adb+['shell','settings','get','global','airplane_mode_on'],text=True).strip()
subprocess.run(adb+['shell','cmd','connectivity','airplane-mode','enable'],check=True)
try:
    subprocess.run(['./tools/test-plugins-emulator.sh'],env={**os.environ,'CALAMUS_PLUGIN_CHECK_MODE':'selection'},check=True)
finally:
    subprocess.run(adb+['shell','cmd','connectivity','airplane-mode','enable' if previous=='1' else 'disable'],check=True)
PY
