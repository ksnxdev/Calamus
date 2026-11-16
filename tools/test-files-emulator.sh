#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-temurin}"
export PATH="$JAVA_HOME/bin:$PATH"
python3 - <<'PY'
from pathlib import Path
import os,subprocess,tempfile,zipfile
root=Path.cwd()
props=dict(line.split('=',1) for line in (root/'local.properties').read_text().splitlines() if '=' in line)
sdk=Path(props['sdk.dir']);bt=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-36/android.jar'
serial='emulator-5580'
mode=os.environ.get('CALAMUS_PLUGIN_CHECK_MODE','full')
adb=[str(sdk/'platform-tools/adb'),'-s',serial]
avd=subprocess.check_output(adb+['emu','avd','name'],text=True)
assert avd.splitlines()[0].strip()=='Calamus_API35'
with tempfile.TemporaryDirectory(prefix='calamus-files-') as tmp:
    tmp=Path(tmp);classes=tmp/'classes';classes.mkdir();dex=tmp/'dex';dex.mkdir()
    def run(args):subprocess.run([str(a) for a in args],check=True)
    sources=list((root/'tests/android-files/src').rglob('*.java'))
    run(['javac','--release','17','-cp',android,'-d',classes,*sources])
    run([bt/'d8','--lib',android,'--min-api','26','--output',dex,*classes.rglob('*.class')])
    apk=tmp/'checks.apk'
    run([bt/'aapt','package','-f','-M',root/'tests/android-files/AndroidManifest.xml','-I',android,'-F',apk])
    with zipfile.ZipFile(apk,'a') as z:z.write(dex/'classes.dex','classes.dex')
    aligned=tmp/'aligned.apk';run([bt/'zipalign','-f','4',apk,aligned])
    run([bt/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-pass','pass:android','--key-pass','pass:android',aligned])
    run(adb+['install','-r','-t',aligned])
    output=subprocess.check_output(adb+['shell','am','instrument','-w','-e','mode',mode,'ksnxdev.calamus.filetest/ksnxdev.calamus.filetest.FileInstrumentation'],text=True)
    print(output)
    if 'File controls passed:' not in output:raise SystemExit('File control checks failed')
    screenshots=root.parent/'emulator/screenshots';screenshots.mkdir(parents=True,exist_ok=True)
    names=['file-controls-light.png','file-controls-dark.png']
    for name in names:
        data=subprocess.check_output(adb+['exec-out','run-as','ksnxdev.calamus','cat','files/'+name])
        (screenshots/name).write_bytes(data)
    print('Saved plugin checks to',screenshots)
PY
