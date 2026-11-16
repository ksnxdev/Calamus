#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-temurin}"
export PATH="$JAVA_HOME/bin:$PATH"
python3 - <<'PY'
from pathlib import Path
import os,subprocess,tempfile,zipfile
root=Path.cwd()
local=dict(line.split('=',1) for line in (root/'local.properties').read_text().splitlines() if '=' in line)
signing=Path(os.environ.get('CALAMUS_SIGNING_PROPERTIES',local.get('CALAMUS_SIGNING_PROPERTIES',str(root.parent/'signing/calamus-signing.properties'))))
keys=dict(line.split('=',1) for line in signing.read_text().splitlines() if '=' in line)
env={**os.environ,**keys}
sdk=Path(local['sdk.dir']);bt=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-36/android.jar'
adb=[str(sdk/'platform-tools/adb'),'-s','emulator-5580']
assert subprocess.check_output(adb+['emu','avd','name'],text=True).splitlines()[0]=='Calamus_API35'
night=subprocess.check_output(adb+['shell','cmd','uimode','night'],text=True).strip().split()[-1]
if night not in ['auto','yes','no','custom']:night='no'
try:
    with tempfile.TemporaryDirectory(prefix='calamus-beta-checks-') as tmp:
        tmp=Path(tmp);classes=tmp/'classes';classes.mkdir();dex=tmp/'dex';dex.mkdir()
        def run(args,**kwargs):subprocess.run([str(a) for a in args],check=True,**kwargs)
        run(['javac','--release','17','-cp',android,'-d',classes,*root.glob('tests/android-beta/src/**/*.java')])
        run([bt/'d8','--lib',android,'--min-api','26','--output',dex,*classes.rglob('*.class')])
        apk=tmp/'checks.apk';run([bt/'aapt','package','-f','-M',root/'tests/android-beta/AndroidManifest.xml','-I',android,'-F',apk])
        with zipfile.ZipFile(apk,'a') as z:z.write(dex/'classes.dex','classes.dex')
        aligned=tmp/'aligned.apk';run([bt/'zipalign','-f','4',apk,aligned])
        run([bt/'apksigner','sign','--ks',keys['CALAMUS_KEYSTORE_PATH'],'--ks-key-alias',keys['CALAMUS_KEY_ALIAS'],'--ks-pass','env:CALAMUS_KEYSTORE_PASSWORD','--key-pass','env:CALAMUS_KEY_PASSWORD',aligned],env=env)
        run(adb+['install','-r','-t',aligned])
        output=subprocess.check_output(adb+['shell','am','instrument','-w','ksnxdev.calamus.betatest/ksnxdev.calamus.betatest.BetaInstrumentation'],text=True)
        print(output)
        if 'Beta checks passed:' not in output:raise SystemExit('Public beta checks failed')
        destination=root.parent/'emulator/screenshots';destination.mkdir(parents=True,exist_ok=True)
        for name in ['beta-intro-light.png','beta-intro-dark.png','beta-icon-render.png','beta-icon-preview.png','beta-about-light.png','beta-about-dark.png','beta-privacy-light.png','beta-privacy-dark.png']:
            data=subprocess.check_output(adb+['exec-out','cat','/sdcard/Android/data/ksnxdev.calamus/files/'+name])
            (destination/name).write_bytes(data)
        print('Saved public beta screenshots to',destination)
finally:
    subprocess.run(adb+['shell','cmd','uimode','night',night],check=False,stdout=subprocess.DEVNULL)
PY
