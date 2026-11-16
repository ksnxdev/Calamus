#!/usr/bin/env python3
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
stubs={
'android/content/Context.java':'package android.content; public class Context {}',
'android/os/SystemClock.java':'package android.os; public class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} }',
'org/json/JSONObject.java':'package org.json; public class JSONObject { private final java.util.Map<String,Object> values=new java.util.HashMap<>(); public JSONObject put(String key,Object value){values.put(key,value);return this;} public long getLong(String key){return ((Number)values.get(key)).longValue();} }',
'org/telegram/messenger/ApplicationLoader.java':'package org.telegram.messenger; public class ApplicationLoader { public static android.content.Context applicationContext=new android.content.Context(); }',
'org/telegram/messenger/UserConfig.java':'package org.telegram.messenger; public class UserConfig { private static final UserConfig[] accounts={new UserConfig(),new UserConfig()}; public long user; public static UserConfig getInstance(int account){return accounts[account];} public long getClientUserId(){return user;} }',
'org/telegram/messenger/MessagesController.java':'package org.telegram.messenger; public class MessagesController { private static final MessagesController[] accounts={new MessagesController(),new MessagesController()}; public boolean offline; public int resets; public static MessagesController getInstance(int account){return accounts[account];} public void applyPluginPresence(boolean value){offline=value;} public void resetPluginPresence(){resets++;} }',
'ksnxdev/calamus/plugins/PluginManager.java':'package ksnxdev.calamus.plugins; public class PluginManager { private static final PluginManager instance=new PluginManager(); public static boolean authorized=true; public static PluginManager getInstance(android.content.Context context){return instance;} public boolean authorizeApiNow(String plugin,String session,String operation,org.json.JSONObject args){return authorized && args.getLong("user_id")>0;} }'
}
with tempfile.TemporaryDirectory(prefix='calamus-presence-') as directory:
    temp=Path(directory);sources=[]
    for name,text in stubs.items():
        file=temp/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text);sources.append(file)
    sources += [root/'TMessagesProj/src/main/java/ksnxdev/calamus/plugins/PluginPresenceController.java',root/'tests/PresenceControllerTest.java']
    classes=temp/'classes';classes.mkdir()
    subprocess.run(['/usr/lib/jvm/java-21-temurin/bin/javac','--release','17','-d',str(classes),*map(str,sources)],check=True)
    subprocess.run(['/usr/lib/jvm/java-21-temurin/bin/java','-cp',str(classes),'PresenceControllerTest'],check=True)
