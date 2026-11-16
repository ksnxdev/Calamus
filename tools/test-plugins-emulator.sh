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
with tempfile.TemporaryDirectory(prefix='calamus-plugins-') as tmp:
    tmp=Path(tmp);classes=tmp/'classes';classes.mkdir();dex=tmp/'dex';dex.mkdir()
    def run(args):subprocess.run([str(a) for a in args],check=True)
    sources=list((root/'tests/android-plugins/src').rglob('*.java'))
    run(['javac','--release','17','-cp',android,'-d',classes,*sources])
    run([bt/'d8','--lib',android,'--min-api','26','--output',dex,*classes.rglob('*.class')])
    apk=tmp/'checks.apk'
    run([bt/'aapt','package','-f','-A',root/'tests/android-plugins/assets','-M',root/'tests/android-plugins/AndroidManifest.xml','-I',android,'-F',apk])
    with zipfile.ZipFile(apk,'a') as z:z.write(dex/'classes.dex','classes.dex')
    aligned=tmp/'aligned.apk';run([bt/'zipalign','-f','4',apk,aligned])
    run([bt/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-pass','pass:android','--key-pass','pass:android',aligned])
    run(adb+['install','-r','-t',aligned])
    output=subprocess.check_output(adb+['shell','am','instrument','-w','-e','mode',mode,'ksnxdev.calamus.plugintest/ksnxdev.calamus.plugintest.PluginInstrumentation'],text=True)
    print(output)
    if ({'cleanup':'Cleanup checks passed:'}.get(mode,'Plugin checks passed:')) not in output:raise SystemExit('Plugin checks failed')
    screenshots=root.parent/'emulator/screenshots';screenshots.mkdir(parents=True,exist_ok=True)
    names=['plugins-light.png','plugins-dark.png','plugin-access-light.png','plugin-access-dark.png','plugin-send-confirmation.png']
    if mode=='example':names=['plugin-install-consent-light.png','plugin-install-consent-dark.png','plugin-install-result-light.png','plugin-install-result-dark.png']
    if mode=='archive':names=[f'archive-{theme}-{kind}.png' for theme in ['light','dark'] for kind in ['history','deleted']]+[f'{kind}-{theme}.png' for theme in ['light','dark'] for kind in ['inline-deleted','deleted-search','deleted-menu','deleted-menu-disabled','deleted-archive-access','composer-input']]
    if mode=='selection':names=[f'select-all-{screen}-{theme}.png' for screen in ['install','access'] for theme in ['light','dark']]
    if mode=='iosmenu':names=[f'ios-message-{screen}-{theme}-{blur}.png' for screen in ['menu','dismiss'] for theme in ['light','dark'] for blur in ['native','snapshot']]
    if mode=='ghostmenu':names=[f'ghost-menu-{theme}.png' for theme in ['light','dark']]+[f'ghost-menu-no-access-{theme}.png' for theme in ['light','dark']]
    if mode=='cleanup':names=[]
    for name in names:
        data=subprocess.check_output(adb+['exec-out','run-as','ksnxdev.calamus','cat','files/'+name])
        (screenshots/name).write_bytes(data)
    print('Saved plugin checks to',screenshots)
PY
