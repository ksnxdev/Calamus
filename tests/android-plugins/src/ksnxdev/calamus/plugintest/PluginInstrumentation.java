package ksnxdev.calamus.plugintest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PluginInstrumentation extends Instrumentation {
    private Context context;
    private ClassLoader loader;
    private Class<?> managerType;
    private Class<?> callbackType;
    private Object manager;
    private String mode;
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); mode=args==null?"":args.getString("mode",""); start(); }
    private interface Request { void run(Object callback) throws Exception; }
    private JSONObject await(Request request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        JSONObject[] value = {null};
        Object cb = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getName().equals("complete")) { value[0] = (JSONObject) args[0]; latch.countDown(); }
            return null;
        });
        request.run(cb);
        if (!latch.await(80, TimeUnit.SECONDS)) throw new AssertionError("Operation timed out");
        return value[0];
    }
    private JSONObject command(String op, String arg) throws Exception {
        return await(cb -> managerType.getMethod("execute", String.class, String.class, callbackType).invoke(manager, op, arg, cb));
    }
    private void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    private JSONObject plugin(JSONObject response, String id) throws Exception {
        check(response.optString("error").isEmpty(), response.optString("error"));
        JSONArray list = response.getJSONArray("plugins");
        for (int i=0; i<list.length(); i++) if (list.getJSONObject(i).getString("id").equals(id)) return list.getJSONObject(i);
        throw new AssertionError("Missing plugin " + id);
    }
    private File fixture(String id, String source, String permissions) throws Exception {
        File file = new File(context.getCacheDir(), id+".plug");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            JSONObject info = new JSONObject().put("id",id).put("name","Isolation check").put("version","1.0").put("description","Adversarial test fixture").put("author","ksnxdev").put("api_version",2).put("permissions",new JSONArray(permissions));
            zip.write(info.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("main.py")); zip.write(source.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("helper.py")); zip.write("VALUE='fixture-module'\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/example.txt")); zip.write("fixture-resource".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        plugin(await(cb -> managerType.getMethod("install", Uri.class, callbackType).invoke(manager, Uri.fromFile(file), cb)),id);
        file.delete(); return file;
    }
    private void grant(String id, String permissions, String scopes) throws Exception {
        JSONObject value = new JSONObject().put("plugin_id",id).put("permissions",new JSONArray(permissions)).put("scopes",new JSONArray(scopes));
        check(command("grant",value.toString()).optString("error").isEmpty(), "Grant failed");
    }
    private JSONObject enable(String id) throws Exception { return plugin(command("enable",id),id); }
    private JSONObject values(String id) throws Exception {
        File file = new File(context.getNoBackupFilesDir(), "isolated_plugins/data/"+id+".json");
        return file.exists() ? new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context=getTargetContext(); loader=context.getClassLoader();
            runMain(() -> type("org.telegram.messenger.ApplicationLoader").getMethod("postInitApplication").invoke(null));
            managerType=type("ksnxdev.calamus.plugins.PluginManager"); callbackType=type("ksnxdev.calamus.plugins.PluginManager$Callback");
            manager=managerType.getMethod("getInstance",Context.class).invoke(null,context);
            if ("ghostmenu".equals(mode)) {
                verifyGhostMenu();
                result.putString("stream","Plugin checks passed: Private View menu ghost switch without commands, two account-wide presence leases, on/off persistence, command-state reflection, revoked permission disables control, native settings synchronization, light/dark. No authenticated Telegram account or live server presence verification.\n");finish(-1,result);return;
            }
            if ("selection".equals(mode)) {
                verifySelectAllChats();
                result.putString("stream","Plugin checks passed: select/deselect all chats in install and access UI, partial selections, 137 chats across two synthetic accounts, explicit confirmation, saved exact grants, scope separation, light/dark. No authenticated Telegram account used.\n");finish(-1,result);return;
            }
            if ("archive".equals(mode)) {
                verifyArchiveMigration();
                verifyTickDelivery();
                verifyArchivePlugin();
                result.putString("stream","Plugin checks passed: white dark-theme composer text, Unicode-safe archive previews, 4096-scope bulk deletion, API 3 isolated archive plugin, delivery during busy asyncio tick, native received/edit/delete notifications, 130-message stream persistence, channel collision separation, scoped archive denial, message actions, ghost command requests, release of sessions, logout cleanup, retained deleted bubbles and labels, chat menu entry stays visible without provider, missing-access explanation and settings link, Unicode archive search UI and scope, reload and revoke, local archive UI light/dark. No authenticated Telegram account or live presence verification.\n"); finish(-1,result);return;
            }
            if ("iosmenu".equals(mode)) {
                verifyIosMenuStandalone();
                result.putString("stream","Plugin checks passed: iOS message preview, reactions above message, status bar insets, light/dark, incoming/outgoing, native/snapshot blur, unified/split long-message scrolling, Copy action, dismissal, standard menu fallback; synthetic account, no live Telegram traffic.\n");finish(-1,result);return;
            }
            if ("example".equals(mode)) {
                verifyInstallConsent();
                result.putString("stream","Plugin checks passed: pre-install review without execution, cancellation preserves state, exact package binding, all requested permissions on consent, single-use tokens, stale review rejection, automatic grants through install UI, legacy update cancellation and confirmation, light/dark.\n");finish(-1,result);return;
            }
            if ("cleanup".equals(mode)) {
                check(command("list", "").optString("error").isEmpty(), "Store initialization failed");
                for (File base : new File[]{context.getFilesDir(), context.getCacheDir(), context.getNoBackupFilesDir()}) {
                    File old = new File(base,"sandbox_runtime");
                    check(!old.exists(), "Cleanup retained " + old.getAbsolutePath() + " canonical=" + old.getCanonicalPath());
                }
                check(new File(context.getFilesDir(),"isolation-preserve/sentinel").exists(),"Cleanup followed a symlink");
                result.putString("stream","Cleanup checks passed: all old test directories removed; symlink target preserved.\n");finish(-1,result);return;
            }
            command("disable_all", "");
            check(command("list", "").getBoolean("isolation_ready"),"Built-in isolation unavailable");
            new File(context.getNoBackupFilesDir(),"security-sentinel.txt").createNewFile();
            fixture("ksnxdev.victim", "from calamus_api import Plugin as Base\nclass Plugin(Base):\n def on_load(self,c):\n  import os\n  c.set('secret','victim-secret')\n  c.set('uid',os.getuid())\n", "[\"storage\"]");
            check(!command("enable","ksnxdev.victim").optString("error").isEmpty(),"Implicit grant accepted");
            grant("ksnxdev.victim","[\"storage\"]","[]");
            JSONObject victim = enable("ksnxdev.victim"); check(victim.getBoolean("enabled"), "Victim load failed: " + victim.optString("error"));
            fixture("ksnxdev.probe", PROBE, "[\"storage\"]"); grant("ksnxdev.probe","[\"storage\"]","[]");
            JSONObject loaded=enable("ksnxdev.probe"); check(loaded.getBoolean("enabled"),"Probe: "+loaded.optString("error"));
            JSONObject probe=values("ksnxdev.probe").getJSONObject("probe");
            for(String key:new String[]{"files","java","native","process","cross_plugin","send","grant","scope","json_depth","fork","install"}) check(probe.getBoolean(key),"Isolation bypass: "+key);
            boolean network=false;
            for(int i=0;i<80;i++){ if(values("ksnxdev.probe").has("network")){ network=values("ksnxdev.probe").getBoolean("network"); break; } Thread.sleep(100); }
            check(network,"Network request was not blocked");
            for(int i=0;i<60 && (!values("ksnxdev.probe").optBoolean("thread") || !values("ksnxdev.probe").optBoolean("async"));i++)Thread.sleep(100);
            check(values("ksnxdev.probe").optBoolean("thread") && values("ksnxdev.probe").optBoolean("async"),"Background tasks did not run");
            check(values("ksnxdev.probe").getString("resource").equals("fixture-resource") && values("ksnxdev.probe").getString("module").equals("fixture-module"),"Package modules/resources failed");
            int probeUid=values("ksnxdev.probe").getInt("uid"), victimUid=values("ksnxdev.victim").getInt("uid");
            check(probeUid!=victimUid && probeUid!=android.os.Process.myUid() && probeUid%100000>=90000,"Plugins share an app UID");
            check(context.checkPermission("android.permission.INTERNET",values("ksnxdev.probe").getInt("pid"),probeUid)==PackageManager.PERMISSION_DENIED,"Isolated process inherited INTERNET");
            check(values("ksnxdev.probe").getString("unicode_echo").equals("Привет 👋"),"Unicode bridge damaged text");
            check(values("ksnxdev.victim").getString("secret").equals("victim-secret"),"Victim data changed");
            verifyPolicy();
            verifyArchives();
            verifyScopedBroker();
            command("disable_all", "");
            fixture("ksnxdev.hang", "from calamus_api import Plugin as Base\nclass Plugin(Base):\n def on_load(self,c):\n  while True: pass\n", "[]");
            grant("ksnxdev.hang","[]","[]"); JSONObject hung=enable("ksnxdev.hang");
            check(!hung.getBoolean("enabled") && !hung.optString("error").isEmpty(),"CPU hang was not stopped");
            fixture("ksnxdev.spoof", "from calamus_api import Plugin as Base\nimport _calamus\nclass Plugin(Base):\n def on_load(self,c):\n  _calamus.request('{\"operation\":\"complete\",\"arguments\":{}}')\n  while True: pass\n", "[]");
            grant("ksnxdev.spoof","[]","[]"); enable("ksnxdev.spoof");
            boolean stopped=false;
            for(int i=0;i<35;i++){ Thread.sleep(1000); if(!plugin(command("list",""),"ksnxdev.spoof").getBoolean("enabled")){stopped=true;break;} }
            check(stopped,"Spoofed completion escaped watchdog");
            fixture("ksnxdev.probe",PROBE,"[\"storage\"]");
            check(!command("enable","ksnxdev.probe").optString("error").isEmpty(),"Updated package reused grant");
            preview();
            verifySendConfirmation();
            command("disable_all", "");
            for(String id:new String[]{"ksnxdev.victim","ksnxdev.probe","ksnxdev.hang","ksnxdev.spoof","ksnxdev.scope","ksnxdev.approval"}) command("remove",id);
            new File(context.getNoBackupFilesDir(),"security-sentinel.txt").delete();
            result.putString("stream","Plugin checks passed: built-in isolation without companion APK, separate UID per plugin, no INTERNET, actual native Python execution, threads/asyncio/relative imports/resources/Unicode, denied device files/Java/native/process/network, cross-plugin storage isolation, bounded JSON parsing, archive traversal/native-file rejection, denied grant/send, scoped broker policy and filtered responses, revoked sessions, update resets grants, CPU watchdog, spoofed completion watchdog, native send confirmation cancellation and account recheck, light/dark UI. No live Telegram account used.\n");
            finish(-1,result);
        } catch(Throwable error) {result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
    }
    private void verifyPolicy() throws Exception {
        Class<?> policy=type("ksnxdev.calamus.plugins.PluginAccessPolicy");
        JSONObject record=new JSONObject().put("enabled",true).put("permissions",new JSONArray("[\"messages.read\"]")).put("scopes",new JSONArray("[{\"account_id\":0,\"user_id\":123,\"dialog_id\":42}]"));
        JSONObject args=new JSONObject("{\"account_id\":0,\"user_id\":123,\"dialog_id\":42}");
        policy.getMethod("authorize",JSONObject.class,String.class,JSONObject.class).invoke(null,record,"history",args);
        for(String op:new String[]{"send_message","delete_message","grant"}) {
            boolean rejected=false;try{policy.getMethod("authorize",JSONObject.class,String.class,JSONObject.class).invoke(null,record,op,args);}catch(java.lang.reflect.InvocationTargetException e){rejected=true;}
            check(rejected,"Policy allowed "+op);
        }
        args.put("dialog_id",43);check(!(boolean)policy.getMethod("scope",JSONObject.class,JSONObject.class,boolean.class).invoke(null,record,args,true),"Other dialog allowed");
        args.put("dialog_id",42).put("user_id",124);check(!(boolean)policy.getMethod("scope",JSONObject.class,JSONObject.class,boolean.class).invoke(null,record,args,true),"Replaced account allowed");
        Class<?> result=type("ksnxdev.calamus.plugins.PluginBridge$Result");
        CountDownLatch done=new CountDownLatch(1);String[] error={""};
        Object cb=Proxy.newProxyInstance(loader,new Class<?>[]{result},(p,m,a)->{if(m.getName().equals("complete")){error[0]=(String)a[1];done.countDown();}return null;});
        runMain(()->managerType.getMethod("api",String.class,String.class,result).invoke(manager,"revoked-token","{\"operation\":\"store.get\",\"arguments\":{\"key\":\"secret\"}}",cb));
        check(done.await(3,TimeUnit.SECONDS)&&!error[0].isEmpty(),"Revoked session allowed");
    }
    private void verifyArchives() throws Exception {
        java.lang.reflect.Method inspect=type("ksnxdev.calamus.plugins.PluginStore").getDeclaredMethod("inspect",File.class);inspect.setAccessible(true);
        for(String path:new String[]{"../escape.py","/escape.py","dir/../../escape.py","dir\\escape.py","bad\n\n.py","addon.so"}){
            File file=new File(context.getCacheDir(),"invalid.plug");
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){zip.putNextEntry(new ZipEntry(path));zip.write(0);zip.closeEntry();}
            boolean rejected=false;
            try{inspect.invoke(null,file);}catch(java.lang.reflect.InvocationTargetException error){String text=error.getCause().getMessage();rejected=text.toLowerCase().contains("path")||text.contains("file type");}
            file.delete();check(rejected,"Unsafe archive accepted: "+path);
        }
    }
    private void verifyScopedBroker() throws Exception {
        Class<?> bridge=type("ksnxdev.calamus.plugins.PluginBridge"), clientType=type("ksnxdev.calamus.plugins.PluginBridge$Client"), resultType=type("ksnxdev.calamus.plugins.PluginBridge$Result");
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        Object client=Proxy.newProxyInstance(loader,new Class<?>[]{clientType},(p,m,a)->{
            if(!m.getName().equals("request"))return null;
            calls.incrementAndGet();String op=(String)a[1];Object value=null;
            if(op.equals("accounts"))value=new JSONArray("[{\"account_id\":0,\"user_id\":123},{\"account_id\":1,\"user_id\":456}]");
            if(op.equals("chats"))value=new JSONArray("[{\"dialog_id\":42},{\"dialog_id\":43}]");
            if(op.equals("history"))value=new JSONArray("[{\"id\":1,\"text\":\"fixture\"}]");
            resultType.getMethod("complete",Object.class,String.class).invoke(a[3],value,"");return null;
        });
        runMain(()->bridge.getMethod("setClient",clientType).invoke(null,client));
        try {
            fixture("ksnxdev.scope", SCOPED,"[\"storage\",\"accounts.read\",\"chats.read\",\"messages.read\"]");
            grant("ksnxdev.scope","[\"storage\",\"accounts.read\",\"chats.read\",\"messages.read\"]","[{\"account_id\":0,\"user_id\":123,\"dialog_id\":42}]");
            JSONObject state=enable("ksnxdev.scope");check(state.getBoolean("enabled"),state.optString("error"));
            JSONObject value=values("ksnxdev.scope");
            check(value.getJSONArray("accounts").length()==1 && value.getJSONArray("accounts").getJSONObject(0).getLong("user_id")==123,"Account list escaped scope");
            check(value.getJSONArray("chats").length()==1 && value.getJSONArray("chats").getJSONObject(0).getLong("dialog_id")==42,"Chat list escaped scope");
            check(value.getBoolean("other_chat") && value.getBoolean("other_user") && value.getBoolean("history_rate") && calls.get()==4,"Rejected request reached client or history flooded");
            command("disable","ksnxdev.scope");
        } finally {runMain(()->type("ksnxdev.calamus.plugins.TelegramPluginHost").getMethod("install",Context.class).invoke(null,context));}
    }
    private static final String SCOPED="""
from calamus_api import Plugin as Base
import json,_calamus
class Plugin(Base):
 def on_load(self,c):
  c.set('accounts',c.accounts())
  a=c.account(0)
  c.set('chats',a.chats())
  a.history(42)
  def raw(**kw): return json.loads(_calamus.request(json.dumps({'operation':'history','arguments':kw})))
  c.set('other_chat',bool(raw(account_id=0,user_id=123,dialog_id=43).get('error')))
  c.set('other_user',bool(raw(account_id=0,user_id=456,dialog_id=42).get('error')))
  c.set('history_rate',bool(raw(account_id=0,user_id=123,dialog_id=42).get('error')))
""";
    private void verifySendConfirmation() throws Exception {
        fixture("ksnxdev.approval","from calamus_api import Plugin as Base\nclass Plugin(Base): pass\n","[\"messages.send\"]");
        grant("ksnxdev.approval","[\"messages.send\"]","[{\"account_id\":0,\"user_id\":123,\"dialog_id\":42}]");
        check(enable("ksnxdev.approval").getBoolean("enabled"),"Approval fixture failed");
        java.lang.reflect.Field field=managerType.getDeclaredField("sessions");field.setAccessible(true);
        String nonce=(String)((java.util.Map<?,?>)field.get(manager)).get("ksnxdev.approval");
        Class<?> host=type("ksnxdev.calamus.plugins.TelegramPluginHost"),result=type("ksnxdev.calamus.plugins.PluginBridge$Result");
        java.lang.reflect.Method method=host.getDeclaredMethod("approveSend",String.class,int.class,long.class,String.class,type("org.telegram.messenger.SendMessagesHelper$SendMessageParams"),JSONObject.class,result);method.setAccessible(true);
        for(String action:new String[]{"Cancel","Send"}){
            CountDownLatch done=new CountDownLatch(1);String[] error={""};
            Object cb=Proxy.newProxyInstance(loader,new Class<?>[]{result},(p,m,a)->{if(m.getName().equals("complete")){error[0]=(String)a[1];done.countDown();}return null;});
            JSONObject args=new JSONObject("{\"account_id\":0,\"user_id\":123,\"dialog_id\":42}").put("_isolation_session",nonce);
            runMain(()->method.invoke(null,"ksnxdev.approval",0,42L,"Synthetic test message; never transmitted",null,args,cb));
            Thread.sleep(500);save("plugin-send-confirmation.png");tap(action);
            check(done.await(3,TimeUnit.SECONDS)&&!error[0].isEmpty(),"Send confirmation did not reject "+action);
            if(action.equals("Send"))check(error[0].contains("Account session changed"),"Account recheck failed");
            Thread.sleep(800);
        }
    }
    private static final String PROBE = """
from calamus_api import Plugin as Base
import json,os,_calamus
class Plugin(Base):
 def on_load(self,c):
  r={}
  for k,f in [('files',lambda: open('/data/user/0/ksnxdev.calamus/no_backup/security-sentinel.txt').read()),('java',lambda: __import__('java')),('native',lambda: __import__('ctypes').CDLL('libc.so')),('process',lambda: __import__('subprocess').run(['/system/bin/sh','-c','id'])),('fork',lambda: os.fork())]:
   try: f(); r[k]=False
   except Exception: r[k]=True
  def raw(op,**args): return json.loads(_calamus.request(json.dumps({'operation':op,'arguments':args})))
  r['cross_plugin']=raw('store.get',key='secret',plugin_id='ksnxdev.victim').get('result') is None
  for key,op in [('send','send_message'),('grant','grant'),('scope','history'),('install','review_confirm')]:
   r[key]=bool(raw(op,account_id=0,user_id=123,dialog_id=42,text='never send',permissions=['messages.send']).get('error'))
  nested=0
  for i in range(80): nested=[nested]
  r['json_depth']=bool(raw('store.set',key='nested',value=nested).get('error'))
  c.set('probe',r)
  try: __import__('socket').socket(); blocked=False
  except Exception: blocked=True
  c.set('network',blocked)
  c.set('uid',os.getuid())
  c.set('pid',os.getpid())
  c.set('unicode','Привет 👋')
  c.set('unicode_echo',c.get('unicode'))
  from .helper import VALUE
  from importlib.resources import files
  from pathlib import Path
  c.set('module',VALUE)
  c.set('resource',files(__package__).joinpath('assets','example.txt').read_text())
  c.set('path_resource',Path(__file__).with_name('assets').joinpath('example.txt').read_text())
  import threading,asyncio
  threading.Thread(target=lambda: c.set('thread',True),daemon=True).start()
  async def background():
   await asyncio.sleep(.05)
   c.set('async',True)
  asyncio.ensure_future(background())
""";
    private interface MainTask {
        void run() throws Exception;
    }

    private void runMain(MainTask task) throws Exception {
        Throwable[] error = {null};
        runOnMainSync(() -> {
            try { task.run(); } catch (Throwable failure) { error[0] = failure; }
        });
        if (error[0] != null) throw new RuntimeException(error[0]);
    }

    private File consentPackage(String id, String version) throws Exception {
        File file=new File(context.getCacheDir(),"consent-fixture-"+id+".plug");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
            JSONObject metadata=new JSONObject().put("id",id).put("name","Consent fixture").put("author","ksnxdev").put("version",version).put("description","Pre-install check").put("api_version",2).put("permissions",new JSONArray("[\"storage\",\"commands\"]"));
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("main.py"));zip.write("from calamus_api import Plugin as Base\nclass Plugin(Base):\n def on_load(self,c): c.set('activated',True)\n".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }return file;
    }
    private JSONObject prepare(File file) throws Exception {
        JSONObject result=await(cb->managerType.getMethod("reviewPackage",Uri.class,callbackType).invoke(manager,Uri.fromFile(file),cb));
        check(result.optString("error").isEmpty(),result.optString("error"));return result.getJSONObject("review");
    }
    private JSONObject confirm(JSONObject proposal) throws Exception {
        return command("review_confirm",new JSONObject().put("token",proposal.getString("token")).put("digest",proposal.getString("digest")).put("scopes",new JSONArray()).put("permissions",new JSONArray("[\"messages.send\"]")).toString());
    }
    private boolean installed(String id) throws Exception {
        JSONArray list=command("list","").getJSONArray("plugins");for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("id").equals(id))return true;return false;
    }
    private void verifyInstallConsent() throws Exception {
        command("disable_all","");for(String id:new String[]{"ksnxdev.consent","ksnxdev.changed","ksnxdev.hello"})command("remove",id);
        File original=consentPackage("ksnxdev.consent","1.0");JSONObject preview=prepare(original);
        check(!installed("ksnxdev.consent")&&!values("ksnxdev.consent").has("activated"),"Review installed or executed code");
        command("review_cancel",preview.getString("token"));check(!installed("ksnxdev.consent"),"Cancel installed a plugin");
        check(command("review_get",preview.getString("token")).optString("error_code").equals("review_expired"),"Cancelled token remained usable");
        preview=prepare(original);consentPackage("ksnxdev.consent","99.0");
        JSONObject first=plugin(confirm(preview),"ksnxdev.consent");check(first.getString("version").equals("1.0"),"Original URI was reopened after consent");
        check(first.getBoolean("enabled")&&first.getJSONArray("granted_permissions").length()==2&&!first.getJSONArray("granted_permissions").toString().contains("messages.send"),"Requested permissions were not bound to the reviewed manifest");
        check(values("ksnxdev.consent").getBoolean("activated"),"Confirmed plugin did not execute");
        check(command("review_confirm",new JSONObject().put("token",preview.getString("token")).put("digest",preview.getString("digest")).put("scopes",new JSONArray()).toString()).optString("error_code").equals("review_expired"),"Consent was replayed");
        JSONObject before=plugin(command("list",""),"ksnxdev.consent");JSONObject update=prepare(consentPackage("ksnxdev.consent","2.0"));
        command("review_cancel",update.getString("token"));JSONObject after=plugin(command("list",""),"ksnxdev.consent");
        check(before.getString("digest").equals(after.getString("digest"))&&after.getBoolean("enabled")&&before.getJSONArray("granted_permissions").toString().equals(after.getJSONArray("granted_permissions").toString()),"Cancelled update changed the installed plugin");
        JSONObject newer=prepare(consentPackage("ksnxdev.consent","2.0"));JSONObject stale=prepare(consentPackage("ksnxdev.consent","3.0"));confirm(newer);
        check(confirm(stale).optString("error_code").equals("install_changed"),"Stale update consent was accepted");command("review_cancel",stale.getString("token"));
        JSONObject tamper=prepare(consentPackage("ksnxdev.changed","1.0"));java.lang.reflect.Field field=managerType.getDeclaredField("reviews");field.setAccessible(true);
        Object pending=((java.util.Map<?,?>)field.get(manager)).get(tamper.getString("token"));java.lang.reflect.Field fileField=pending.getClass().getDeclaredField("file");fileField.setAccessible(true);
        try(FileOutputStream output=new FileOutputStream((File)fileField.get(pending))){output.write("changed".getBytes(StandardCharsets.UTF_8));}
        check(confirm(tamper).optString("error_code").equals("package_changed")&&!installed("ksnxdev.changed"),"Changed staged bytes were accepted");
        original.delete();new File(context.getCacheDir(),"consent-fixture-ksnxdev.changed.plug").delete();command("remove","ksnxdev.consent");
        verifyConsentUi();
    }
    private void verifyConsentUi() throws Exception {
        context.startActivity(new Intent().setClassName("ksnxdev.calamus","org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity activity=null;for(int i=0;i<100&&activity==null;i++){Thread.sleep(100);activity=(Activity)type("org.telegram.ui.LaunchActivity").getField("instance").get(null);}
        check(activity!=null,"No activity");final Activity launch=activity;
        Class<?> theme=type("org.telegram.ui.ActionBar.Theme");Object saved=theme.getMethod("getCurrentTheme").invoke(null);boolean previousDark=(boolean)saved.getClass().getMethod("isDark").invoke(saved);
        try{
            for(boolean dark:new boolean[]{false,true}){
                if(dark){
                    File old=new File(context.getCacheDir(),"legacy-example.plug");
                    try(java.io.InputStream input=getContext().getAssets().open("hello-2.0.0.plug");FileOutputStream output=new FileOutputStream(old)){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
                    await(cb->managerType.getMethod("install",Uri.class,callbackType).invoke(manager,Uri.fromFile(old),cb));old.delete();grant("ksnxdev.hello","[]","[]");enable("ksnxdev.hello");
                }
                JSONObject oldState=dark?plugin(command("list",""),"ksnxdev.hello"):null;
                runMain(()->{
                    Object selected=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,selected,false,dark);
                    Object fragment=type("ksnxdev.calamus.plugins.ui.PluginsActivity").getConstructor().newInstance();launch.getClass().getMethod("presentFragment",type("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,fragment);
                });Thread.sleep(1000);
                for(int attempt=0;attempt<2;attempt++){
                    File selected=new File(context.getCacheDir(),"selected-plugin.plug");
                    try(java.io.InputStream input=getContext().getAssets().open("hello-2.1.0.plug");FileOutputStream output=new FileOutputStream(selected)){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
                    JSONObject reviewed=prepare(selected);selected.delete();
                    runMain(()->{Object fragment=type("ksnxdev.calamus.plugins.ui.PluginInstallActivity").getConstructor(String.class).newInstance(reviewed.getString("token"));launch.getClass().getMethod("presentFragment",type("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,fragment);});
                    requireText("Requested permissions");Thread.sleep(500);
                    Rect identity=new Rect(),heading=new Rect();requireText("Hello Calamus").getBoundsInScreen(identity);requireText("Install plugin").getBoundsInScreen(heading);check(identity.top>heading.bottom,"Reviewed plugin name is hidden behind the header");
                    Object current=type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(null);check(current.getClass().getSimpleName().equals("PluginInstallActivity"),"Manual permission screen opened before consent");
                    check(dark?plugin(command("list",""),"ksnxdev.hello").getString("digest").equals(oldState.getString("digest")):!installed("ksnxdev.hello"),"Package was installed before confirmation");
                    StringBuilder tree=new StringBuilder();dumpNode(getUiAutomation().getRootInActiveWindow(),tree);
                    for(String title:new String[]{"Save plugin data","Show notifications","Read selected chat messages"})check(tree.toString().contains(title),"Missing permission in confirmation: "+title);
                    if(attempt==0){save("plugin-install-consent-"+(dark?"dark":"light")+".png");tap("Cancel");Thread.sleep(500);}
                    else tap(dark?"Update":"Install");
                }
                boolean enabled=false;JSONObject info=null;
                for(int i=0;i<60;i++){Thread.sleep(100);if(installed("ksnxdev.hello")){info=plugin(command("list",""),"ksnxdev.hello");if(info.optBoolean("enabled")){enabled=true;break;}}}
                check(enabled,"Confirmed example failed: "+(info==null?"missing":info.optString("error")));
                check(info.getJSONArray("granted_permissions").length()==info.getJSONArray("permissions").length()&&info.getJSONArray("scopes").length()==0,"All requested permissions were not granted after confirmation");
                check(info.getJSONObject("commands").has("ping"),"Optional feature permission was not granted");Thread.sleep(700);save("plugin-install-result-"+(dark?"dark":"light")+".png");command("disable","ksnxdev.hello");
            }
        }finally{runMain(()->theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,saved,false,previousDark));command("remove","ksnxdev.hello");}
    }
    private void preview() throws Exception {
        context.startActivity(new Intent().setClassName("ksnxdev.calamus", "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity activity=null;
        for(int i=0;i<100&&activity==null;i++){Thread.sleep(100);activity=(Activity)type("org.telegram.ui.LaunchActivity").getField("instance").get(null);}
        check(activity!=null,"Activity unavailable");final Activity launch=activity;
        Class<?> theme=type("org.telegram.ui.ActionBar.Theme");Object saved=theme.getMethod("getCurrentTheme").invoke(null);boolean savedDark=(boolean)saved.getClass().getMethod("isDark").invoke(saved);
        try {
            for(boolean dark:new boolean[]{false,true}){
                runMain(()->{
                    Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");
                    theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);
                    Object fragment=type("ksnxdev.calamus.plugins.ui.PluginsActivity").getConstructor().newInstance();
                    launch.getClass().getMethod("presentFragment",type("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,fragment);
                });
                Thread.sleep(1600);save("plugins-"+(dark?"dark":"light")+".png");
                runMain(()->{
                    Object fragment=type("ksnxdev.calamus.plugins.ui.PluginAccessActivity").getConstructor(String.class).newInstance("ksnxdev.probe");
                    launch.getClass().getMethod("presentFragment",type("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch,fragment);
                });
                Thread.sleep(1000);save("plugin-access-"+(dark?"dark":"light")+".png");
            }
        } finally {runMain(()->theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,saved,false,savedDark));}
    }
    private AccessibilityNodeInfo requireText(String text) {
        for (int attempt = 0; attempt < 50; attempt++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo match = findNode(root, text);
                if (match != null) return match;
            }
            SystemClock.sleep(100);
        }
        StringBuilder tree = new StringBuilder();
        dumpNode(getUiAutomation().getRootInActiveWindow(), tree);
        throw new AssertionError("Missing UI text: " + text + "\n" + tree);
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        String value = node.getText() == null ? "" : node.getText().toString();
        if (value.equalsIgnoreCase(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private void dumpNode(AccessibilityNodeInfo node, StringBuilder output) {
        if (node == null) return;
        output.append(node.getClassName()).append(" | ").append(node.getText()).append(" | ").append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) dumpNode(node.getChild(i), output);
    }

    private void tap(String text) {
        Rect bounds = new Rect();
        requireText(text).getBoundsInScreen(bounds);
        long time = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(time, time + action * 80L, action, bounds.centerX(), bounds.centerY(), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            getUiAutomation().injectInputEvent(event, true);
            event.recycle();
        }
    }

    private void save(String name) throws Exception {
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new AssertionError("Screenshot failed");
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), name))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        screenshot.recycle();
    }
    private String archiveSession(String id) throws Exception {
        java.lang.reflect.Field field=managerType.getDeclaredField("sessions");field.setAccessible(true);
        return (String)((java.util.Map<?,?>)field.get(manager)).get(id);
    }
    private JSONObject archiveScope(long dialog) throws Exception { return new JSONObject().put("account_id",0).put("user_id",123).put("dialog_id",dialog); }
    private JSONObject archiveQuery(String id,JSONObject args) throws Exception {
        Class<?> resultType=type("ksnxdev.calamus.plugins.PluginBridge$Result");CountDownLatch done=new CountDownLatch(1);JSONObject[] reply={null};String[] failure={""};
        Object cb=Proxy.newProxyInstance(loader,new Class<?>[]{resultType},(p,m,a)->{if(m.getName().equals("complete")){reply[0]=(JSONObject)a[0];failure[0]=(String)a[1];done.countDown();}return null;});
        managerType.getMethod("archiveRequest",String.class,String.class,String.class,JSONObject.class,resultType).invoke(manager,id,archiveSession(id),"archive.query",args,cb);
        check(done.await(15,TimeUnit.SECONDS),"Archive query timed out");
        return new JSONObject().put("result",reply[0]).put("error",failure[0]);
    }
    private void archiveEvent(String name,JSONObject payload) throws Exception {
        managerType.getMethod("event",String.class,JSONObject.class).invoke(manager,name,payload);command("list","");
    }
    private android.view.View ghostCell(android.view.View view) throws Exception {
        if(type("org.telegram.ui.Cells.TextCheckCell").isInstance(view))return view;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.view.View found=ghostCell(group.getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private boolean ghostEnabled(String plugin,int account) throws Exception {
        return (boolean)type("ksnxdev.calamus.plugins.PluginPresenceController").getMethod("enabledForPlugin",String.class,int.class).invoke(null,plugin,account);
    }
    private void awaitGhost(String plugin,boolean first,boolean second) throws Exception {
        for(int i=0;i<100&&(ghostEnabled(plugin,0)!=first||ghostEnabled(plugin,1)!=second);i++)Thread.sleep(50);
        check(ghostEnabled(plugin,0)==first&&ghostEnabled(plugin,1)==second,"Ghost menu did not apply account-wide state");Thread.sleep(500);
    }
    private void verifyGhostMenu() throws Exception {
        String id="ksnxdev.calamus_archive";command("disable_all","");command("remove",id);
        context.startActivity(new Intent().setClassName("ksnxdev.calamus","org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity launch=null;for(int i=0;i<100&&launch==null;i++){Thread.sleep(100);launch=(Activity)type("org.telegram.ui.LaunchActivity").getField("instance").get(null);}check(launch!=null,"No launch activity");final Activity activity=launch;
        Class<?> configType=type("org.telegram.messenger.UserConfig"),userType=type("org.telegram.tgnet.TLRPC$User"),theme=type("org.telegram.ui.ActionBar.Theme");
        java.lang.reflect.Field currentUser=configType.getDeclaredField("currentUser"),clientId=configType.getDeclaredField("clientUserId");currentUser.setAccessible(true);clientId.setAccessible(true);
        Object[] configs=new Object[2],users=new Object[2];long[] identities=new long[2];Object previous=theme.getMethod("getCurrentTheme").invoke(null);
        for(int a=0;a<2;a++){configs[a]=configType.getMethod("getInstance",int.class).invoke(null,a);users[a]=currentUser.get(configs[a]);identities[a]=clientId.getLong(configs[a]);check(users[a]==null,"Ghost menu check requires an unauthenticated emulator");}
        String permissions="[\"storage\",\"notifications\",\"commands\",\"accounts.read\",\"messages.read\",\"messages.archive\",\"messages.menu\",\"ui\",\"presence.control\",\"presence.read\"]";
        String scopes="[{\"account_id\":0,\"user_id\":123,\"dialog_id\":42},{\"account_id\":1,\"user_id\":124,\"dialog_id\":43}]";
        try{
            runMain(()->{for(int a=0;a<2;a++){Object user=type("org.telegram.tgnet.TLRPC$TL_user").getConstructor().newInstance();userType.getField("id").setLong(user,123+a);userType.getField("first_name").set(user,"Ghost account "+a);currentUser.set(configs[a],user);clientId.setLong(configs[a],123+a);}});
            File archive=new File(context.getCacheDir(),"ghost-menu.plug");try(java.io.InputStream input=getContext().getAssets().open("calamus-archive.plug");FileOutputStream output=new FileOutputStream(archive)){byte[] data=new byte[4096];int n;while((n=input.read(data))!=-1)output.write(data,0,n);}
            plugin(await(cb->managerType.getMethod("install",Uri.class,callbackType).invoke(manager,Uri.fromFile(archive),cb)),id);archive.delete();grant(id,permissions,scopes);check(enable(id).getBoolean("enabled"),"Private View did not load");awaitGhost(id,false,false);
            for(boolean dark:new boolean[]{false,true}){
                runMain(()->{Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);});
                Object screen=type("ksnxdev.calamus.plugins.ui.PluginsActivity").getConstructor().newInstance();presentSelectionScreen(activity,screen);Thread.sleep(650);tap("Private View");requireText("Ghost mode");tap("Ghost mode");awaitGhost(id,true,true);
                JSONObject state=plugin(command("list",""),id);check(state.getJSONObject("setting_values").getBoolean("ghost"),"Menu did not persist ghost setting");
                android.app.Dialog dialog=(android.app.Dialog)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getVisibleDialog").invoke(screen);android.view.View cell=ghostCell(dialog.getWindow().getDecorView());check(cell!=null&&(boolean)cell.getClass().getMethod("isChecked").invoke(cell),"Menu switch does not show actual enabled state");save("ghost-menu-"+(dark?"dark":"light")+".png");
                tap("Ghost mode");awaitGhost(id,false,false);tap("Close");Thread.sleep(400);
                managerType.getMethod("dispatchCommand",String.class,JSONObject.class).invoke(manager,".ghost on",archiveScope(42));awaitGhost(id,true,false);tap("Private View");requireText("Ghost mode");
                dialog=(android.app.Dialog)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getVisibleDialog").invoke(screen);cell=ghostCell(dialog.getWindow().getDecorView());check((boolean)cell.getClass().getMethod("isChecked").invoke(cell)==false,"Mixed-account state should not indicate all accounts enabled");
                tap("Ghost mode");awaitGhost(id,true,true);tap("Ghost mode");awaitGhost(id,false,false);tap("Close");Thread.sleep(400);
                Object settings=type("ksnxdev.calamus.plugins.ui.PluginSettingsActivity").getConstructor(String.class).newInstance(id);presentSelectionScreen(activity,settings);Thread.sleep(600);tap("Режим призрака для выбранных аккаунтов");awaitGhost(id,true,true);tap("Режим призрака для выбранных аккаунтов");awaitGhost(id,false,false);
                runMain(()->type("org.telegram.ui.ActionBar.BaseFragment").getMethod("finishFragment").invoke(settings));Thread.sleep(700);
                String restricted=permissions.replace(",\"presence.control\"","");grant(id,restricted,scopes);check(enable(id).getBoolean("enabled"),"Optional permission denial broke plugin");
                Object latest=plugin(command("list",""),id);runMain(()->{java.lang.reflect.Method show=screen.getClass().getDeclaredMethod("showPlugin",JSONObject.class);show.setAccessible(true);show.invoke(screen,latest);});requireText("Ghost mode");
                dialog=(android.app.Dialog)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getVisibleDialog").invoke(screen);cell=ghostCell(dialog.getWindow().getDecorView());check(!cell.isEnabled()&&!(boolean)cell.getClass().getMethod("isChecked").invoke(cell),"Ghost menu remains enabled without permission");save("ghost-menu-no-access-"+(dark?"dark":"light")+".png");tap("Close");
                grant(id,permissions,scopes);check(enable(id).getBoolean("enabled"),"Private View did not resume");awaitGhost(id,false,false);
            }
        }finally{
            command("remove",id);
            runMain(()->{for(int a=0;a<2;a++){currentUser.set(configs[a],users[a]);clientId.setLong(configs[a],identities[a]);Object connection=type("org.telegram.tgnet.ConnectionsManager").getMethod("getInstance",int.class).invoke(null,a);connection.getClass().getMethod("cleanup",boolean.class).invoke(connection,false);}theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,previous,false,(boolean)previous.getClass().getMethod("isDark").invoke(previous));});
        }
    }

    private void presentSelectionScreen(Activity activity,Object fragment) throws Exception {
        boolean[] shown={false};
        for(int attempt=0;attempt<40&&!shown[0];attempt++){
            runMain(()->shown[0]=(boolean)activity.getClass().getMethod("presentFragment",type("org.telegram.ui.ActionBar.BaseFragment"),boolean.class,boolean.class).invoke(activity,fragment,false,true));
            if(!shown[0])Thread.sleep(100);
        }
        check(shown[0],"Could not present selection screen after transition");
    }
    private void awaitSelection(java.util.Set<?> selected,int count) throws Exception {
        for(int i=0;i<40&&selected.size()!=count;i++)Thread.sleep(50);
        check(selected.size()==count,"Selection did not settle: "+selected.size()+" expected "+count);Thread.sleep(500);
    }
    private void verifySelectAllChats() throws Exception {
        String id="ksnxdev.check.selection";command("disable_all","");command("remove",id);
        fixture(id,"from calamus_api import Plugin as Base\nclass Plugin(Base):\n def on_load(self,context): pass\n","[\"accounts.read\",\"messages.read\"]");
        context.startActivity(new Intent().setClassName("ksnxdev.calamus","org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity launch=null;for(int i=0;i<100&&launch==null;i++){Thread.sleep(100);launch=(Activity)type("org.telegram.ui.LaunchActivity").getField("instance").get(null);}check(launch!=null,"No launch activity");final Activity activity=launch;
        Class<?> configType=type("org.telegram.messenger.UserConfig"),userType=type("org.telegram.tgnet.TLRPC$User"),controllerType=type("org.telegram.messenger.MessagesController"),theme=type("org.telegram.ui.ActionBar.Theme");
        java.lang.reflect.Field currentUser=configType.getDeclaredField("currentUser"),clientId=configType.getDeclaredField("clientUserId");currentUser.setAccessible(true);clientId.setAccessible(true);
        Object[] configs=new Object[2],controllers=new Object[2],users=new Object[2],folders=new Object[2];long[] identities=new long[2];java.util.Map<?,?>[] caches=new java.util.Map<?,?>[2];
        Object previous=theme.getMethod("getCurrentTheme").invoke(null);
        for(int a=0;a<2;a++){
            configs[a]=configType.getMethod("getInstance",int.class).invoke(null,a);users[a]=currentUser.get(configs[a]);identities[a]=clientId.getLong(configs[a]);check(users[a]==null,"Selection UI check requires an unauthenticated emulator");
            controllers[a]=controllerType.getMethod("getInstance",int.class).invoke(null,a);folders[a]=((android.util.SparseArray<?>)controllerType.getField("dialogsByFolder").get(controllers[a])).get(0);caches[a]=new java.util.HashMap<>((java.util.Map<?,?>)field(controllers[a],"users"));
        }
        try{
            runMain(()->{
                for(int a=0;a<2;a++){
                    Object self=type("org.telegram.tgnet.TLRPC$TL_user").getConstructor().newInstance();userType.getField("id").setLong(self,123+a);userType.getField("first_name").set(self,"Selection account "+a);currentUser.set(configs[a],self);clientId.setLong(configs[a],123+a);
                    java.util.ArrayList<Object> dialogs=new java.util.ArrayList<>();
                    for(int i=0;i<(a==0?130:5);i++){
                        long peer=50000+a*1000+i;Object user=type("org.telegram.tgnet.TLRPC$TL_user").getConstructor().newInstance();userType.getField("id").setLong(user,peer);userType.getField("first_name").set(user,"Selection chat "+a+"-"+i);controllerType.getMethod("putUser",userType,boolean.class).invoke(controllers[a],user,false);
                        Object dialog=type("org.telegram.tgnet.TLRPC$TL_dialog").getConstructor().newInstance();type("org.telegram.tgnet.TLRPC$Dialog").getField("id").setLong(dialog,peer);dialogs.add(dialog);
                    }
                    ((android.util.SparseArray<Object>)controllerType.getField("dialogsByFolder").get(controllers[a])).put(0,dialogs);
                }
            });
            JSONArray available=(JSONArray)type("ksnxdev.calamus.plugins.TelegramPluginHost").getMethod("accessOptions").invoke(null);check(available.length()==137,"Chat choices truncated above 100 or across accounts: "+available.length());
            for(boolean dark:new boolean[]{false,true}){
                runMain(()->{Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);});
                JSONObject review=command("review_installed",id).getJSONObject("review");Object install=type("ksnxdev.calamus.plugins.ui.PluginInstallActivity").getConstructor(String.class).newInstance(review.getString("token"));
                presentSelectionScreen(activity,install);Thread.sleep(650);
                java.util.Set<Integer> selected=(java.util.Set<Integer>)field(install,"selected");int previousCount=plugin(command("list",""),id).getJSONArray("scopes").length();
                tap("Select All");awaitSelection(selected,137);requireText("Deselect All");check(selected.size()==137,"Install select-all omitted chats: "+selected.size()+" of "+((JSONArray)field(install,"choices")).length());save("select-all-install-"+(dark?"dark":"light")+".png");
                check(plugin(command("list",""),id).getJSONArray("scopes").length()==previousCount,"Selection was granted before confirmation");
                tap("Deselect All");awaitSelection(selected,0);requireText("Select All");check(selected.isEmpty(),"Install deselect-all failed");tap("Select All");awaitSelection(selected,137);requireText("Deselect All");tap("Update");Thread.sleep(900);
                JSONObject approved=plugin(command("list",""),id);check(approved.getBoolean("enabled")&&approved.getJSONArray("scopes").length()==137,"Install confirmation failed: enabled="+approved.optBoolean("enabled")+", scopes="+approved.getJSONArray("scopes").length()+", error="+approved.optString("error")+", screen="+field(install,"error")+", selected="+selected.size());
                Object access=type("ksnxdev.calamus.plugins.ui.PluginAccessActivity").getConstructor(String.class).newInstance(id);presentSelectionScreen(activity,access);Thread.sleep(700);
                for(int wait=0;wait<60&&!(boolean)field(access,"loaded");wait++)Thread.sleep(50);java.util.Set<Integer> scopes=(java.util.Set<Integer>)field(access,"scopes");check(scopes.size()==137,"Access screen lost saved grants: "+scopes.size()+", choices="+((JSONArray)field(access,"choices")).length()+", error="+field(access,"error"));tap("Deselect All");awaitSelection(scopes,0);requireText("Select All");check(scopes.isEmpty(),"Access deselect-all failed");tap("Select All");awaitSelection(scopes,137);requireText("Deselect All");check(scopes.size()==137,"Access select-all omitted chats");
                save("select-all-access-"+(dark?"dark":"light")+".png");
                runMain(()->{Class<?> item=type("org.telegram.ui.Components.UItem");Object row=item.getMethod("asButton",int.class,CharSequence.class).invoke(null,1000,"Saved Messages");java.lang.reflect.Method click=access.getClass().getDeclaredMethod("onClick",item,android.view.View.class,int.class,float.class,float.class);click.setAccessible(true);click.invoke(access,row,null,0,0f,0f);});
                awaitSelection(scopes,136);check(scopes.size()==136,"Individual deselection after select-all failed");requireText("Select All");tap("Select All");awaitSelection(scopes,137);requireText("Deselect All");check(scopes.size()==137,"Select-all did not complete partial selection");
                runMain(()->{Class<?> item=type("org.telegram.ui.Components.UItem");Object row=item.getMethod("asButton",int.class,CharSequence.class).invoke(null,1000,"Saved Messages");java.lang.reflect.Method click=access.getClass().getDeclaredMethod("onClick",item,android.view.View.class,int.class,float.class,float.class);click.setAccessible(true);click.invoke(access,row,null,0,0f,0f);});
                awaitSelection(scopes,136);tap("Save and enable");Thread.sleep(900);JSONObject saved=plugin(command("list",""),id);check(saved.getJSONArray("scopes").length()==136,"Access save lost bulk grant");
                Class<?> policy=type("ksnxdev.calamus.plugins.PluginAccessPolicy");java.lang.reflect.Method contains=policy.getMethod("scope",JSONObject.class,JSONObject.class,boolean.class);
                check(!(boolean)contains.invoke(null,saved,new JSONObject().put("account_id",0).put("user_id",123).put("dialog_id",123),true),"Deselected chat remained granted");
                check((boolean)contains.invoke(null,saved,new JSONObject().put("account_id",1).put("user_id",124).put("dialog_id",51004),true),"Second-account chat not granted");
                check(!(boolean)contains.invoke(null,saved,new JSONObject().put("account_id",0).put("user_id",124).put("dialog_id",50000),true),"Bulk grants crossed account identity");
            }
        }finally{
            command("remove",id);
            runMain(()->{for(int a=0;a<2;a++){currentUser.set(configs[a],users[a]);clientId.setLong(configs[a],identities[a]);android.util.SparseArray<Object> folder=(android.util.SparseArray<Object>)controllerType.getField("dialogsByFolder").get(controllers[a]);if(folders[a]==null)folder.remove(0);else folder.put(0,folders[a]);java.util.Map<Object,Object> cache=(java.util.Map<Object,Object>)field(controllers[a],"users");cache.clear();cache.putAll((java.util.Map)caches[a]);Object connections=type("org.telegram.tgnet.ConnectionsManager").getMethod("getInstance",int.class).invoke(null,a);connections.getClass().getMethod("cleanup",boolean.class).invoke(connections,false);}theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,previous,false,(boolean)previous.getClass().getMethod("isDark").invoke(previous));});
        }
    }

    private void verifyArchiveMigration() throws Exception {
        String id="ksnxdev.check.archive_migration";
        File file=new File(context.getNoBackupFilesDir(),"isolated_plugins/archives/"+id+".db");file.getParentFile().mkdirs();android.database.sqlite.SQLiteDatabase.deleteDatabase(file);
        try{
            try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)){
                db.execSQL("CREATE TABLE journal (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL,dialog_id INTEGER NOT NULL,message_id INTEGER NOT NULL,channel_id INTEGER NOT NULL,date INTEGER NOT NULL,edit_date INTEGER NOT NULL,captured INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,text TEXT NOT NULL,media TEXT NOT NULL)");
                for(int i=1;i<=35;i++)db.execSQL("INSERT INTO journal(user_id,dialog_id,message_id,channel_id,date,edit_date,captured,deleted,text,media) VALUES(123,42,?,0,1700000000,0,1700000000000,1,?,'')",new Object[]{i,i==1?"Старый Ёжик %_ из архива":"Другое сообщение "+i});
            }
            Class<?> store=type("ksnxdev.calamus.plugins.PluginArchiveStore");java.lang.reflect.Method query=store.getMethod("request",Context.class,String.class,String.class,JSONObject.class);
            JSONObject page=(JSONObject)query.invoke(null,context,id,"archive.query",archiveScope(42).put("deleted_only",true));
            check(page.getJSONArray("rows").length()==20&&page.getJSONArray("rows").getJSONObject(0).getInt("message_id")==35,"Old archive migration changed records");
            JSONObject search=(JSONObject)query.invoke(null,context,id,"archive.query",archiveScope(42).put("deleted_only",true).put("search","еЖИК %_"));
            check(search.getJSONArray("rows").length()==1&&search.getJSONArray("rows").getJSONObject(0).getInt("message_id")==1,"Search did not find legacy record outside first page");
            JSONObject other=(JSONObject)query.invoke(null,context,id,"archive.query",archiveScope(-77).put("deleted_only",true).put("search","ежик"));
            check(other.getJSONArray("rows").length()==0,"Legacy search crossed dialog scope");
            String unicode="а".repeat(179)+"🙂"+"конец";
            JSONObject sample=new JSONObject().put("id",99).put("dialog_id",42).put("channel_id",0).put("archivable",true).put("text",unicode);
            query.invoke(null,context,id,"archive.record",archiveScope(42).put("message",sample));
            JSONObject preview=(JSONObject)query.invoke(null,context,id,"archive.query",archiveScope(42).put("message_id",99));
            String shortened=preview.getJSONArray("rows").getJSONObject(0).getString("text");check(shortened.endsWith("🙂")&&shortened.codePointCount(0,shortened.length())==180,"Archive preview split an emoji");
            query.invoke(null,context,id,"archive.record",archiveScope(-77).put("message",new JSONObject(sample.toString()).put("id",1).put("dialog_id",-77).put("channel_id",77)));
            query.invoke(null,context,id,"archive.record",archiveScope(777).put("message",new JSONObject(sample.toString()).put("id",777).put("dialog_id",777)));
            query.invoke(null,context,id,"archive.record",archiveScope(42).put("user_id",124).put("message",new JSONObject(sample.toString()).put("id",2)));
            JSONArray grants=new JSONArray().put(archiveScope(42));for(int i=1;i<4096;i++)grants.put(archiveScope(100000+i));JSONArray deleted=new JSONArray();for(int i=1;i<=1000;i++)deleted.put(i);
            java.lang.reflect.Method remove=store.getDeclaredMethod("deleted",Context.class,String.class,long.class,long.class,JSONArray.class,JSONArray.class);remove.setAccessible(true);
            long started=android.os.SystemClock.elapsedRealtime();JSONArray affected=(JSONArray)remove.invoke(null,context,id,123L,0L,deleted,grants);
            check(android.os.SystemClock.elapsedRealtime()-started<10000,"Large chat selection blocked message deletion processing");
            check(affected.length()==1&&affected.getJSONObject(0).getJSONArray("ids").length()==36,"Bulk deletion did not target only stored, granted messages");
            JSONObject ungranted=(JSONObject)query.invoke(null,context,id,"archive.query",archiveScope(777).put("deleted_only",true));check(ungranted.getJSONArray("rows").length()==0,"Bulk deletion marked an ungranted chat");
            JSONObject channel=(JSONObject)query.invoke(null,context,id,"archive.query",archiveScope(-77).put("deleted_only",true));check(channel.getJSONArray("rows").length()==0,"Bulk deletion crossed channel namespace");
            JSONObject identity=(JSONObject)query.invoke(null,context,id,"archive.query",archiveScope(42).put("user_id",124).put("deleted_only",true));check(identity.getJSONArray("rows").length()==0,"Bulk deletion crossed account identity");
        }finally{android.database.sqlite.SQLiteDatabase.deleteDatabase(file);}
    }

    private void verifyTickDelivery() throws Exception {
        String id="ksnxdev.check.tick_delivery";
        command("disable_all","");command("remove",id);
        try {
            fixture(id,"import asyncio\nimport time\nfrom calamus_api import Plugin as BasePlugin\nclass Plugin(BasePlugin):\n def on_load(self,context):\n  self.context=context\n  context.register_command('tick_ping','Tick delivery check')\n  asyncio.get_event_loop().call_later(0.5,self.tick)\n def tick(self):\n  self.context.log('tick-busy')\n  time.sleep(1.5)\n def on_command(self,name,arguments,chat):\n  self.context.set('delivered',True)\n","[\"storage\",\"commands\"]");
            grant(id,"[\"storage\",\"commands\"]","[{\"account_id\":0,\"user_id\":123,\"dialog_id\":42}]");
            check(enable(id).getBoolean("enabled"),"Tick fixture failed to load");
            boolean busy=false;
            for(int i=0;i<100&&!busy;i++){
                JSONObject info=plugin(command("list",""),id);busy=info.optJSONArray("logs")!=null&&info.getJSONArray("logs").toString().contains("tick-busy");
                if(!busy)Thread.sleep(25);
            }
            check(busy,"Async worker tick did not start");
            JSONArray commands=(JSONArray)managerType.getMethod("commandsForChat",JSONObject.class).invoke(manager,archiveScope(42));
            JSONObject selected=null;for(int i=0;i<commands.length();i++)if(commands.getJSONObject(i).getString("plugin_id").equals(id))selected=commands.getJSONObject(i);
            check(selected!=null,"Tick command was not registered");JSONObject action=selected;
            JSONObject reply=await(cb->managerType.getMethod("runCommand",JSONObject.class,String.class,JSONObject.class,callbackType).invoke(manager,action,"",archiveScope(42),cb));
            check(plugin(reply,id).getBoolean("enabled"),"Event arriving during async tick disabled plugin: "+plugin(reply,id).optString("error"));
            check(values(id).optBoolean("delivered"),"Event arriving during async tick was lost");
        } finally {command("remove",id);}
    }
    private void verifyArchivePlugin() throws Exception {
        final String id="ksnxdev.calamus_archive";command("disable_all","");command("remove",id);
        Class<?> bridge=type("ksnxdev.calamus.plugins.PluginBridge"),clientType=type("ksnxdev.calamus.plugins.PluginBridge$Client"),resultType=type("ksnxdev.calamus.plugins.PluginBridge$Result");
        java.util.List<Boolean> presence=new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger showArchive=new java.util.concurrent.atomic.AtomicInteger();
        Object client=Proxy.newProxyInstance(loader,new Class<?>[]{clientType},(p,m,a)->{
            if(!m.getName().equals("request"))return null;
            String plugin=(String)a[0],operation=(String)a[1];JSONObject args=(JSONObject)a[2];Object cb=a[3];
            if(operation.startsWith("archive.")&&!operation.equals("archive.show")){
                managerType.getMethod("archiveRequest",String.class,String.class,String.class,JSONObject.class,resultType).invoke(manager,plugin,args.getString("_isolation_session"),operation,args,cb);return null;
            }
            Object value=true;
            if(operation.equals("accounts"))value=new JSONArray("[{\"account_id\":0,\"user_id\":123,\"name\":\"Archive fixture\"}]");
            if(operation.equals("presence.set")){presence.add(args.getBoolean("enabled"));value=new JSONObject().put("enabled",args.getBoolean("enabled"));}
            if(operation.equals("presence.get"))value=new JSONObject().put("exact",false).put("kind","recently").put("by_me",true);
            if(operation.equals("archive.show"))showArchive.incrementAndGet();
            resultType.getMethod("complete",Object.class,String.class).invoke(cb,value,"");return null;
        });
        runMain(()->bridge.getMethod("setClient",clientType).invoke(null,client));
        try {
            File archive=new File(context.getCacheDir(),"calamus-archive.plug");
            try(java.io.InputStream input=getContext().getAssets().open("calamus-archive.plug");FileOutputStream out=new FileOutputStream(archive)){byte[] data=new byte[4096];int n;while((n=input.read(data))!=-1)out.write(data,0,n);}
            plugin(await(cb->managerType.getMethod("install",Uri.class,callbackType).invoke(manager,Uri.fromFile(archive),cb)),id);archive.delete();
            String permissions="[\"storage\",\"notifications\",\"commands\",\"accounts.read\",\"messages.read\",\"messages.archive\",\"messages.menu\",\"ui\",\"presence.control\",\"presence.read\"]";
            grant(id,permissions,"[{\"account_id\":0,\"user_id\":123,\"dialog_id\":42},{\"account_id\":0,\"user_id\":123,\"dialog_id\":-77}]");
            JSONObject loaded=enable(id);check(loaded.getBoolean("enabled"),loaded.optString("error"));
            JSONObject first=new JSONObject().put("id",101).put("dialog_id",42).put("channel_id",0).put("sender_id",42).put("text","Original message · Привет 👋").put("date",System.currentTimeMillis()/1000-60).put("edit_date",0).put("archivable",true);
            archiveEvent("message.received",archiveScope(42).put("messages",new JSONArray().put(first)));
            JSONObject changed=new JSONObject(first.toString()).put("text","Edited message").put("edit_date",System.currentTimeMillis()/1000-30);
            archiveEvent("message.updated",archiveScope(42).put("messages",new JSONArray().put(changed)));
            archiveEvent("message.updated",archiveScope(42).put("messages",new JSONArray().put(changed)));
            JSONObject query=archiveQuery(id,archiveScope(42).put("message_id",101));check(query.optString("error").isEmpty(),query.optString("error"));
            JSONArray versions=query.getJSONObject("result").getJSONArray("rows");check(versions.length()==2,"Edit versions missing or duplicate");
            JSONObject original=archiveQuery(id,archiveScope(42).put("version_id",versions.getJSONObject(1).getLong("id"))).getJSONObject("result").getJSONArray("rows").getJSONObject(0);
            check(original.getString("text").equals(first.getString("text")),"Original text damaged");
            check(!archiveQuery(id,archiveScope(43)).optString("error").isEmpty(),"Archive escaped chat scope");
            check(!archiveQuery(id,archiveScope(42).put("user_id",124)).optString("error").isEmpty(),"Archive escaped account scope");
            archiveEvent("message.received",archiveScope(42).put("messages",new JSONArray().put(new JSONObject(first.toString()).put("id",102).put("archivable",false))));
            check(archiveQuery(id,archiveScope(42).put("message_id",102)).getJSONObject("result").getJSONArray("rows").length()==0,"Self-destructing content archived");
            JSONObject channel=new JSONObject(first.toString()).put("dialog_id",-77).put("channel_id",77).put("text","Channel message");
            archiveEvent("message.received",archiveScope(-77).put("messages",new JSONArray().put(channel)));
            archiveEvent("message.deleted",archiveScope(0).put("channel_id",0).put("ids",new JSONArray().put(101)));
            check(archiveQuery(id,archiveScope(42).put("deleted_only",true)).getJSONObject("result").getJSONArray("rows").length()==1,"Deleted message not found");
            check(archiveQuery(id,archiveScope(-77).put("deleted_only",true)).getJSONObject("result").getJSONArray("rows").length()==0,"Channel ID collision");
            archiveEvent("message.deleted",archiveScope(0).put("channel_id",77).put("ids",new JSONArray().put(101)));
            check(archiveQuery(id,archiveScope(-77).put("deleted_only",true)).getJSONObject("result").getJSONArray("rows").length()==1,"Channel deletion missing");
            JSONArray actions=(JSONArray)managerType.getMethod("messageActionsForChat",JSONObject.class).invoke(manager,archiveScope(42));check(actions.length()==1&&actions.getJSONObject(0).getString("title").equals("История изменений"),"Message menu action missing");
            check(((JSONArray)managerType.getMethod("messageActionsForChat",JSONObject.class).invoke(manager,archiveScope(43))).length()==0,"Message actions escaped scope");
            managerType.getMethod("runMessageAction",JSONObject.class,JSONObject.class,JSONObject.class).invoke(manager,actions.getJSONObject(0),archiveScope(42),changed);command("list","");
            check(showArchive.get()==1,"Message history action did not open archive");
            JSONArray commands=(JSONArray)managerType.getMethod("commandsForChat",JSONObject.class).invoke(manager,archiveScope(42));
            for(String argument:new String[]{"on","off"}){
                JSONObject ghost=null;for(int i=0;i<commands.length();i++)if(commands.getJSONObject(i).getString("name").equals("ghost"))ghost=commands.getJSONObject(i);
                check(ghost!=null,"Ghost command missing");JSONObject selected=ghost;
                JSONObject response=await(cb->managerType.getMethod("runCommand",JSONObject.class,String.class,JSONObject.class,callbackType).invoke(manager,selected,argument,archiveScope(42),cb));check(response.optString("error").isEmpty(),response.optString("error"));
            }
            check(presence.size()==2&&presence.get(0)&&!presence.get(1),"Ghost command did not control presence");
            command("disable",id);check(((JSONArray)managerType.getMethod("messageActionsForChat",JSONObject.class).invoke(manager,archiveScope(42))).length()==0,"Disabled menu action remains");
            check(enable(id).getBoolean("enabled"),"Archive plugin did not resume");
            check(archiveQuery(id,archiveScope(42).put("message_id",101)).getJSONObject("result").getJSONArray("rows").length()==2,"Archive lost on restart");
            verifyArchiveUi(id);
            archiveEvent("account.changed",new JSONObject().put("account_id",0).put("user_id",123).put("logged_in",false));
            check(!plugin(command("list",""),id).getBoolean("enabled"),"Logout did not disable plugin");
            JSONObject cleared=(JSONObject)type("ksnxdev.calamus.plugins.PluginArchiveStore").getMethod("request",Context.class,String.class,String.class,JSONObject.class).invoke(null,context,id,"archive.query",archiveScope(42));
            check(cleared.getJSONArray("rows").length()==0,"Logout retained account archive");
        } finally {command("remove",id);runMain(()->type("ksnxdev.calamus.plugins.TelegramPluginHost").getMethod("install",Context.class).invoke(null,context));}
    }
    private Object presentFixtureChat(Activity activity) throws Exception {
        Object[] chat={null};
        runMain(()->{
            Class<?> userType=type("org.telegram.tgnet.TLRPC$User");Object peer=type("org.telegram.tgnet.TLRPC$TL_user").getConstructor().newInstance();userType.getField("id").setLong(peer,42);userType.getField("first_name").set(peer,"Archive peer");
            Object controller=type("org.telegram.messenger.MessagesController").getMethod("getInstance",int.class).invoke(null,0);controller.getClass().getMethod("putUser",userType,boolean.class).invoke(controller,peer,false);
            chat[0]=type("org.telegram.ui.ChatActivity").getMethod("of",long.class).invoke(null,42L);type("org.telegram.ui.ActionBar.BaseFragment").getMethod("setCurrentAccount",int.class).invoke(chat[0],0);
            activity.getClass().getMethod("presentFragment",type("org.telegram.ui.ActionBar.BaseFragment"),boolean.class,boolean.class).invoke(activity,chat[0],false,true);
        });
        return chat[0];
    }
    private Object verifyNativeMessageMenu(Activity activity) throws Exception {
        Object[] chat={presentFixtureChat(activity)};
        Thread.sleep(5200);
        runMain(()->{
            Class<?> messageType=type("org.telegram.tgnet.TLRPC$Message"),peerType=type("org.telegram.tgnet.TLRPC$Peer");
            Object peer=type("org.telegram.tgnet.TLRPC$TL_peerUser").getConstructor().newInstance();peerType.getField("user_id").setLong(peer,42);
            Object message=type("org.telegram.tgnet.TLRPC$TL_message").getConstructor().newInstance();messageType.getField("id").setInt(message,101);messageType.getField("dialog_id").setLong(message,42);messageType.getField("peer_id").set(message,peer);messageType.getField("from_id").set(message,peer);messageType.getField("message").set(message,"Edited message");messageType.getField("date").setInt(message,(int)(System.currentTimeMillis()/1000));
            Class<?> objectType=type("org.telegram.messenger.MessageObject");Object object=objectType.getConstructor(int.class,messageType,boolean.class,boolean.class).newInstance(0,message,false,false);
            java.lang.reflect.Field selected=type("org.telegram.ui.ChatActivity").getDeclaredField("selectedObject");selected.setAccessible(true);selected.set(chat[0],object);
            java.util.ArrayList<Integer> icons=new java.util.ArrayList<>(),options=new java.util.ArrayList<>();java.util.ArrayList<CharSequence> labels=new java.util.ArrayList<>();
            chat[0].getClass().getMethod("fillMessageMenu",objectType,java.util.ArrayList.class,java.util.ArrayList.class,java.util.ArrayList.class).invoke(chat[0],object,icons,labels,options);
            int index=-1;for(int i=0;i<labels.size();i++)if("История изменений".contentEquals(labels.get(i)))index=i;
            check(index>=0&&options.get(index)>=70000,"Native message context menu did not contain plugin history action");
            chat[0].getClass().getMethod("processSelectedOption",int.class).invoke(chat[0],options.get(index));
        });
        check(command("list","").optString("error").isEmpty(),"Message menu callback failed");
        check(archiveQuery("ksnxdev.calamus_archive",archiveScope(42).put("message_id",101)).getJSONObject("result").getJSONArray("rows").length()==2,"Opening message history duplicated unchanged text");
        return chat[0];
    }
    private Object field(Object object,String name) throws Exception {
        java.lang.reflect.Field value=object.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(object);
    }
    private Object testMessage(int id,String text) throws Exception {
        Class<?> messageType=type("org.telegram.tgnet.TLRPC$Message"),peerType=type("org.telegram.tgnet.TLRPC$Peer");
        Object from=type("org.telegram.tgnet.TLRPC$TL_peerUser").getConstructor().newInstance();peerType.getField("user_id").setLong(from,42);
        Object to=type("org.telegram.tgnet.TLRPC$TL_peerUser").getConstructor().newInstance();peerType.getField("user_id").setLong(to,123);
        Object message=type("org.telegram.tgnet.TLRPC$TL_message").getConstructor().newInstance();messageType.getField("id").setInt(message,id);messageType.getField("dialog_id").setLong(message,42);messageType.getField("peer_id").set(message,to);messageType.getField("from_id").set(message,from);messageType.getField("message").set(message,text);messageType.getField("date").setInt(message,(int)(System.currentTimeMillis()/1000));
        return type("org.telegram.messenger.MessageObject").getConstructor(int.class,messageType,boolean.class,boolean.class).newInstance(0,message,true,false);
    }
    private android.view.View deletedCell(android.view.View view,Class<?> cellType) throws Exception {
        if(cellType.isInstance(view)){
            Object message=cellType.getMethod("getMessageObject").invoke(view);
            if(message!=null&&(int)message.getClass().getMethod("getId").invoke(message)==502)return view;
        }
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.view.View found=deletedCell(group.getChildAt(i),cellType);if(found!=null)return found;}
        return null;
    }
    private void verifyComposerColor(Object chat,boolean dark) throws Exception {
        Object composer=field(chat,"chatActivityEnterView");android.widget.TextView editor=(android.widget.TextView)composer.getClass().getMethod("getEditField").invoke(composer);CharSequence original=editor.getText().toString();
        try{
            runMain(()->{
                composer.getClass().getMethod("setFieldText",CharSequence.class).invoke(composer,"Проверка ввода 👋");
                int color=editor.getCurrentTextColor();check(dark?color==android.graphics.Color.WHITE:color!=android.graphics.Color.WHITE,"Composer text has incorrect light/dark color");
                check(color!=editor.getCurrentHintTextColor(),"Typed text is indistinguishable from the placeholder");
            });Thread.sleep(300);save("composer-input-"+(dark?"dark":"light")+".png");
        }finally{runMain(()->composer.getClass().getMethod("setFieldText",CharSequence.class).invoke(composer,original));}
    }

    private void verifyDeletedStyle(Object chat) throws Exception {
        runMain(()->{
            Class<?> cellType=type("org.telegram.ui.Cells.ChatMessageCell"),objectType=type("org.telegram.messenger.MessageObject");
            android.view.View root=(android.view.View)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getFragmentView").invoke(chat),cell=deletedCell(root,cellType);
            check(cell!=null,"Retained message was not rendered in the chat");
            Object message=cellType.getMethod("getMessageObject").invoke(cell);
            int key=type("org.telegram.ui.ActionBar.Theme").getField("key_chat_messageTextIn").getInt(null);
            int muted=(int)cellType.getMethod("getThemedColor",int.class).invoke(cell,key);
            objectType.getField("calamusDeleted").setBoolean(message,false);
            int regular=(int)cellType.getMethod("getThemedColor",int.class).invoke(cell,key);
            objectType.getField("calamusDeleted").setBoolean(message,true);
            check(muted!=regular,"Retained message uses ordinary text color");
            java.lang.reflect.Field time=cellType.getDeclaredField("currentTimeString");time.setAccessible(true);
            check(time.get(cell).toString().contains("deleted"),"Retained bubble has no deleted label");
        });
    }

    private void verifyInlineDeleted(Activity activity,Object chat,String plugin) throws Exception {
        Class<?> chatType=type("org.telegram.ui.ChatActivity"),objectType=type("org.telegram.messenger.MessageObject"),centerType=type("org.telegram.messenger.NotificationCenter");
        Object message=testMessage(502,"Удалённое сообщение: Ёжик %_ проверка");
        runMain(()->{
            Object top=type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(activity);
            if(top!=chat)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("finishFragment").invoke(top);
        });
        Thread.sleep(800);
        runMain(()->{
            java.util.ArrayList<Object> messages=(java.util.ArrayList<Object>)chatType.getField("messages").get(chat);
            android.util.SparseArray<Object>[] dictionaries=(android.util.SparseArray<Object>[])chatType.getField("messagesDict").get(chat);
            messages.add(0,message);dictionaries[0].put(502,message);
            String date=(String)objectType.getField("dateKey").get(message);
            java.util.Map<String,java.util.ArrayList<Object>> days=(java.util.Map<String,java.util.ArrayList<Object>>)field(chat,"messagesByDays");
            days.computeIfAbsent(date,key->new java.util.ArrayList<>()).add(0,message);
            Object adapter=field(chat,"chatAdapter");java.lang.reflect.Method notify=adapter.getClass().getMethod("notifyDataSetChanged",boolean.class);notify.setAccessible(true);notify.invoke(adapter,true);
            Object center=centerType.getMethod("getInstance",int.class).invoke(null,0);
            centerType.getMethod("postNotificationName",int.class,Object[].class).invoke(center,centerType.getField("messagesDeleted").getInt(null),new Object[]{new java.util.ArrayList<>(java.util.List.of(502)),0L,false});
            objectType.getField("deleted").setBoolean(message,true);
        });
        command("list","");Thread.sleep(600);
        android.util.SparseArray<Object>[] dictionaries=(android.util.SparseArray<Object>[])chatType.getField("messagesDict").get(chat);
        check(dictionaries[0].get(502)!=null,"Deleted message disappeared from native chat");
        check(objectType.getField("calamusDeleted").getBoolean(dictionaries[0].get(502)),"Retained message has no deleted marker");
        check(!objectType.getField("deleted").getBoolean(dictionaries[0].get(502)),"Controller deletion flag still hides retained content");
        check(!(boolean)objectType.getMethod("canSetReaction").invoke(dictionaries[0].get(502)),"Retained message can send reactions");
        check(!(boolean)objectType.getMethod("canForwardMessage").invoke(dictionaries[0].get(502)),"Retained message can be forwarded");
        check(archiveQuery(plugin,archiveScope(42).put("message_id",502).put("deleted_only",true)).getJSONObject("result").getJSONArray("rows").length()==1,"Native retention was not persisted");
        check(archiveQuery(plugin,archiveScope(42).put("deleted_only",true).put("search","ежИК %_")).getJSONObject("result").getJSONArray("rows").length()==1,"Unicode or literal search failed");
        check(archiveQuery(plugin,archiveScope(-77).put("deleted_only",true).put("search","ёжик")).getJSONObject("result").getJSONArray("rows").length()==0,"Deleted search crossed chat scope");
        runMain(()->{
            Object normal=testMessage(503,"Обычное сообщение");
            ((java.util.ArrayList<Object>)chatType.getField("messages").get(chat)).add(0,normal);dictionaries[0].put(503,normal);
            String date=(String)objectType.getField("dateKey").get(normal);((java.util.Map<String,java.util.ArrayList<Object>>)field(chat,"messagesByDays")).get(date).add(0,normal);
        });
        Class<?> theme=type("org.telegram.ui.ActionBar.Theme");
        for(boolean dark:new boolean[]{false,true}){
            runMain(()->{
                Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);
                activity.getClass().getMethod("rebuildAllFragments",boolean.class).invoke(activity,true);
            });Thread.sleep(900);verifyDeletedStyle(chat);verifyComposerColor(chat,dark);save("inline-deleted-"+(dark?"dark":"light")+".png");
            runMain(()->((android.view.View)field(chat,"headerItem")).performClick());Thread.sleep(500);
            requireText("View deleted messages");save("deleted-menu-"+(dark?"dark":"light")+".png");tap("View deleted messages");
            Thread.sleep(900);requireText("Deleted messages");
            Object fragment=type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(activity);
            java.lang.reflect.Field searchField=type("uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry").getDeclaredField("searchItem");searchField.setAccessible(true);Object search=searchField.get(fragment);
            runMain(()->{
                search.getClass().getMethod("openSearch",boolean.class).invoke(search,true);
                ((android.widget.EditText)search.getClass().getMethod("getSearchField").invoke(search)).setText("ежик %_");
            });Thread.sleep(800);requireText("Удалённое сообщение: Ёжик %_ проверка");
            check(((JSONArray)field(fragment,"rows")).length()==1,"Search UI did not filter deleted messages");save("deleted-search-"+(dark?"dark":"light")+".png");
            runMain(()->type("org.telegram.ui.ActionBar.BaseFragment").getMethod("finishFragment").invoke(fragment));Thread.sleep(700);
            runMain(()->{
                android.view.View root=(android.view.View)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getFragmentView").invoke(chat);
                android.view.View cell=deletedCell(root,type("org.telegram.ui.Cells.ChatMessageCell"));check(cell!=null,"Retained cell missing before context menu");
                java.lang.reflect.Method menu=chatType.getDeclaredMethod("createMenu",android.view.View.class,boolean.class,boolean.class,float.class,float.class,boolean.class,boolean.class,boolean.class);menu.setAccessible(true);
                check((boolean)menu.invoke(chat,cell,false,false,30f,30f,true,true,false),"Retained message context menu did not open");
            });Thread.sleep(400);tap("Message edit history");Thread.sleep(800);requireText("Message edit history");
            Object history=type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(activity);
            check(((JSONObject)field(history,"scope")).getInt("message_id")==502,"Retained context menu opened history for the wrong message");
            requireText("Удалённое сообщение: Ёжик %_ проверка");
            runMain(()->type("org.telegram.ui.ActionBar.BaseFragment").getMethod("finishFragment").invoke(history));Thread.sleep(700);
        }
        runMain(()->{
            ((java.util.ArrayList<?>)chatType.getField("messages").get(chat)).clear();dictionaries[0].clear();
            ((java.util.Map<?,?>)field(chat,"messagesByDays")).clear();((android.util.SparseArray<?>)field(chat,"messagesByDaysSorted")).clear();
            java.lang.reflect.Method refresh=chatType.getDeclaredMethod("refreshDeletedArchive");refresh.setAccessible(true);refresh.invoke(chat);
        });Thread.sleep(800);
        check(dictionaries[0].get(502)!=null&&objectType.getField("calamusDeleted").getBoolean(dictionaries[0].get(502)),"Deleted message did not reload from archive");
        command("disable",plugin);Thread.sleep(400);check(dictionaries[0].get(502)==null,"Disabled plugin retained visible private messages");
        for(boolean dark:new boolean[]{false,true}){
            runMain(()->{
                Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);
                activity.getClass().getMethod("rebuildAllFragments",boolean.class).invoke(activity,true);
            });Thread.sleep(800);
            runMain(()->((android.view.View)field(chat,"headerItem")).performClick());Thread.sleep(400);requireText("View deleted messages");save("deleted-menu-disabled-"+(dark?"dark":"light")+".png");tap("View deleted messages");
            requireText("Deleted messages");requireText("Enable Private View and allow it to access messages and the local archive for this chat in its access settings.");save("deleted-archive-access-"+(dark?"dark":"light")+".png");tap("Plugins");requireText("Private View");
            Object settings=type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(activity);check(settings.getClass().getSimpleName().equals("PluginsActivity"),"Missing archive access did not open plugin settings");
            runMain(()->type("org.telegram.ui.ActionBar.BaseFragment").getMethod("finishFragment").invoke(settings));Thread.sleep(800);
        }
        check(enable(plugin).getBoolean("enabled"),"Plugin did not resume after retention test");Thread.sleep(800);
        runMain(()->type("ksnxdev.calamus.plugins.TelegramPluginHost").getMethod("showArchive",type("org.telegram.ui.ActionBar.BaseFragment"),int.class,long.class,int.class).invoke(null,chat,0,43L,0));
        requireText("Enable Private View and allow it to access messages and the local archive for this chat in its access settings.");tap("Close");Thread.sleep(500);
        check(dictionaries[0].get(502)!=null,"Re-enabled plugin did not reload deleted archive");
    }

    private void verifyArchiveUi(String plugin) throws Exception {
        context.startActivity(new Intent().setClassName("ksnxdev.calamus","org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity launch=null;for(int i=0;i<100&&launch==null;i++){Thread.sleep(100);launch=(Activity)type("org.telegram.ui.LaunchActivity").getField("instance").get(null);}check(launch!=null,"No activity");final Activity activity=launch;
        Class<?> configType=type("org.telegram.messenger.UserConfig");Object config=configType.getMethod("getInstance",int.class).invoke(null,0);
        java.lang.reflect.Field userField=configType.getDeclaredField("currentUser");userField.setAccessible(true);Object savedUser=userField.get(config);check(savedUser==null,"Synthetic archive UI requires an unauthenticated emulator");
        java.lang.reflect.Field clientId=configType.getDeclaredField("clientUserId");clientId.setAccessible(true);long savedId=clientId.getLong(config);
        Class<?> theme=type("org.telegram.ui.ActionBar.Theme");Object previous=theme.getMethod("getCurrentTheme").invoke(null);
        try {
            Object fake=type("org.telegram.tgnet.TLRPC$TL_user").getConstructor().newInstance();type("org.telegram.tgnet.TLRPC$User").getField("id").setLong(fake,123);type("org.telegram.tgnet.TLRPC$User").getField("first_name").set(fake,"Archive fixture");
            runMain(()->{userField.set(config,fake);clientId.setLong(config,123);});
            verifyNativeArchivePipeline(plugin);
            Object nativeChat=verifyNativeMessageMenu(activity);
            Thread.sleep(1200);
            verifyInlineDeleted(activity,nativeChat,plugin);
            for(boolean dark:new boolean[]{false,true}){
                for(boolean history:new boolean[]{false,true}){
                    JSONObject scope=archiveScope(42).put("deleted_only",!history).put("message_id",history?101:0);
                    runMain(()->{Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);Object fragment=type("ksnxdev.calamus.plugins.ui.PluginArchiveActivity").getConstructor(String.class,String.class,JSONObject.class).newInstance(plugin,archiveSession(plugin),scope);activity.getClass().getMethod("presentFragment",type("org.telegram.ui.ActionBar.BaseFragment"),boolean.class,boolean.class).invoke(activity,fragment,false,true);});
                    Thread.sleep(900);requireText(history?"Message edit history":"Deleted messages");requireText("Edited message");save("archive-"+(dark?"dark":"light")+"-"+(history?"history":"deleted")+".png");
                }
            }
        }finally{runMain(()->{userField.set(config,savedUser);clientId.setLong(config,savedId);type("org.telegram.tgnet.ConnectionsManager").getMethod("getInstance",int.class).invoke(null,0).getClass().getMethod("cleanup",boolean.class).invoke(type("org.telegram.tgnet.ConnectionsManager").getMethod("getInstance",int.class).invoke(null,0),false);theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,previous,false,(boolean)previous.getClass().getMethod("isDark").invoke(previous));});}
    }

    private android.view.View messageCell(android.view.View root,int id) throws Exception {
        Class<?> cellType=type("org.telegram.ui.Cells.ChatMessageCell");
        if(cellType.isInstance(root)){Object message=cellType.getMethod("getMessageObject").invoke(root);if(message!=null&&(int)message.getClass().getMethod("getId").invoke(message)==id)return root;}
        if(root instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.view.View result=messageCell(group.getChildAt(i),id);if(result!=null)return result;}
        return null;
    }
    private void verifyIosMenuStandalone() throws Exception {
        context.startActivity(new Intent().setClassName("ksnxdev.calamus","org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity launch=null;for(int i=0;i<100&&launch==null;i++){Thread.sleep(100);launch=(Activity)type("org.telegram.ui.LaunchActivity").getField("instance").get(null);}check(launch!=null,"No activity");final Activity activity=launch;
        Class<?> configType=type("org.telegram.messenger.UserConfig");Object config=configType.getMethod("getInstance",int.class).invoke(null,0);
        java.lang.reflect.Field currentUser=configType.getDeclaredField("currentUser");currentUser.setAccessible(true);Object savedUser=currentUser.get(config);check(savedUser==null,"iOS checks require unauthenticated emulator");java.lang.reflect.Field clientId=configType.getDeclaredField("clientUserId");clientId.setAccessible(true);long savedId=clientId.getLong(config);
        Class<?> theme=type("org.telegram.ui.ActionBar.Theme");Object savedTheme=theme.getMethod("getActiveTheme").invoke(null);
        try{
            runMain(()->{Object user=type("org.telegram.tgnet.TLRPC$TL_user").getConstructor().newInstance();type("org.telegram.tgnet.TLRPC$User").getField("id").setLong(user,123);type("org.telegram.tgnet.TLRPC$User").getField("first_name").set(user,"iOS fixture");currentUser.set(config,user);clientId.setLong(config,123);});
            Object chat=presentFixtureChat(activity);Thread.sleep(1800);verifyIosMessageMenu(activity,chat);
        }finally{runMain(()->{currentUser.set(config,savedUser);clientId.setLong(config,savedId);theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,savedTheme,false,(boolean)savedTheme.getClass().getMethod("isDark").invoke(savedTheme));Object connections=type("org.telegram.tgnet.ConnectionsManager").getMethod("getInstance",int.class).invoke(null,0);connections.getClass().getMethod("cleanup",boolean.class).invoke(connections,false);});}
    }
    private void verifyIosMessageMenu(Activity activity,Object chat) throws Exception {
        Class<?> chatType=type("org.telegram.ui.ChatActivity"),objectType=type("org.telegram.messenger.MessageObject"),theme=type("org.telegram.ui.ActionBar.Theme");
        Object config=type("uz.unnarsx.cherrygram.core.configs.CherrygramMessagesConfig").getField("INSTANCE").get(null);
        boolean savedMenu=(boolean)config.getClass().getMethod("getBlurMessageMenuBackground").invoke(config),savedUnified=(boolean)config.getClass().getMethod("getMsgMenuUnifiedScroll").invoke(config),savedNative=(boolean)config.getClass().getMethod("getMsgMenuNativeBlur").invoke(config);
        Object media=type("org.telegram.messenger.MediaDataController").getMethod("getInstance",int.class).invoke(null,0);
        java.util.List<Object> enabledReactions=(java.util.List<Object>)media.getClass().getMethod("getEnabledReactionsList").invoke(media);java.util.ArrayList<Object> savedReactions=new java.util.ArrayList<>(enabledReactions);
        java.util.Map<String,Object> reactionMap=(java.util.Map<String,Object>)media.getClass().getMethod("getReactionsMap").invoke(media);java.util.Map<String,Object> savedReactionMap=new java.util.HashMap<>(reactionMap);
        try{
            runMain(()->{
                if(enabledReactions.isEmpty())for(String emoji:new String[]{"👍","❤️","🔥","🥰","👏","😁"}){
                    Class<?> reactionType=type("org.telegram.tgnet.TLRPC$TL_availableReaction");Object reaction=reactionType.getConstructor().newInstance();reactionType.getField("reaction").set(reaction,emoji);
                    for(String key:new String[]{"static_icon","appear_animation","select_animation","activate_animation","effect_animation","around_animation","center_icon"}){Object document=type("org.telegram.tgnet.TLRPC$TL_document").getConstructor().newInstance();type("org.telegram.tgnet.TLRPC$Document").getField("mime_type").set(document,"application/x-tgsticker");reactionType.getField(key).set(reaction,document);}
                    enabledReactions.add(reaction);reactionMap.put(emoji,reaction);
                }
            });
            for(boolean dark:new boolean[]{false,true})for(boolean nativeBlur:new boolean[]{false,true}){
                runMain(()->{
                    config.getClass().getMethod("setBlurMessageMenuBackground",boolean.class).invoke(config,true);config.getClass().getMethod("setMsgMenuUnifiedScroll",boolean.class).invoke(config,nativeBlur);config.getClass().getMethod("setMsgMenuNativeBlur",boolean.class).invoke(config,nativeBlur);
                    Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);activity.getClass().getMethod("rebuildAllFragments",boolean.class).invoke(activity,true);
                });Thread.sleep(800);
                String content="Сообщение для выделения как в iOS 👋";
                if(dark)for(int line=1;line<=36;line++)content+="\nСтрока длинного сообщения "+line;
                Object message=testMessage(507,content);Object owner=objectType.getField("messageOwner").get(message);type("org.telegram.tgnet.TLRPC$Message").getField("out").setBoolean(owner,!nativeBlur);
                java.lang.reflect.Field outgoingCache=objectType.getDeclaredField("isOutOwnerCached");outgoingCache.setAccessible(true);outgoingCache.set(message,null);check((boolean)objectType.getMethod("isOutOwner").invoke(message)==!nativeBlur,"Fixture direction is incorrect");
                runMain(()->{
                    java.util.ArrayList<Object> messages=(java.util.ArrayList<Object>)chatType.getField("messages").get(chat);messages.removeIf(item->{try{return (int)objectType.getMethod("getId").invoke(item)==507;}catch(Exception e){throw new RuntimeException(e);}});messages.add(0,message);
                    android.util.SparseArray<Object>[] dictionaries=(android.util.SparseArray<Object>[])chatType.getField("messagesDict").get(chat);dictionaries[0].put(507,message);
                    String date=(String)objectType.getField("dateKey").get(message);java.util.Map<String,java.util.ArrayList<Object>> days=(java.util.Map<String,java.util.ArrayList<Object>>)field(chat,"messagesByDays");days.computeIfAbsent(date,key->new java.util.ArrayList<>()).add(0,message);
                    Object adapter=field(chat,"chatAdapter");java.lang.reflect.Method notify=adapter.getClass().getMethod("notifyDataSetChanged",boolean.class);notify.setAccessible(true);notify.invoke(adapter,true);
                    activity.getClass().getMethod("rebuildAllFragments",boolean.class).invoke(activity,true);
                    Object list=field(chat,"chatListView");list.getClass().getMethod("scrollToPosition",int.class).invoke(list,0);
                });Thread.sleep(500);
                runMain(()->{
                    android.view.View root=(android.view.View)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getFragmentView").invoke(chat);android.view.View cell=messageCell(root,507);check(cell!=null,"iOS fixture message not visible");
                    java.lang.reflect.Method menu=chatType.getDeclaredMethod("createMenu",android.view.View.class,boolean.class,boolean.class,float.class,float.class,boolean.class,boolean.class,boolean.class);menu.setAccessible(true);check((boolean)menu.invoke(chat,cell,false,false,30f,30f,true,true,false),"iOS message menu did not open");
                });Thread.sleep(700);
                Object popup=field(chat,"scrimPopupWindow");check(popup!=null,"iOS popup missing");android.view.View root=(android.view.View)popup.getClass().getMethod("getContentView").invoke(popup);
                android.view.View preview=root.findViewWithTag("calamus_ios_message_preview"),actions=root.findViewWithTag("calamus_ios_message_actions");check(preview!=null&&actions!=null,"iOS preview/actions absent");android.graphics.Rect bounds=new android.graphics.Rect();check(preview.getGlobalVisibleRect(bounds)&&bounds.width()>50&&bounds.height()>20,"iOS preview is clipped");
                android.view.View reactions=root.findViewWithTag("calamus_ios_message_reactions");check(reactions!=null,"iOS reaction panel missing");int[] reactionPos=new int[2];reactions.getLocationOnScreen(reactionPos);check(reactionPos[1]<bounds.top,"Reactions are not above the selected message");
                check(reactionPos[1]>=type("org.telegram.messenger.AndroidUtilities").getField("statusBarHeight").getInt(null),"Reactions overlap the system status bar");
                if(dark&&!nativeBlur){check(preview.getParent() instanceof android.widget.ScrollView,"Split preview is not independently scrollable");check(((android.widget.ScrollView)preview.getParent()).getScrollY()>0,"Long message did not scroll to its end");}
                save("ios-message-menu-"+(dark?"dark":"light")+"-"+(nativeBlur?"native":"snapshot")+".png");
                if(dark&&nativeBlur){
                    android.view.View container=(android.view.View)actions.getParent();while(!(container.getParent() instanceof android.widget.ScrollView))container=(android.view.View)container.getParent();android.widget.ScrollView scroll=(android.widget.ScrollView)container.getParent();runMain(()->scroll.fullScroll(android.view.View.FOCUS_DOWN));Thread.sleep(350);tap("Copy");
                }else runMain(()->{if(!nativeBlur)popup.getClass().getMethod("dismiss",boolean.class).invoke(popup,false);else chatType.getMethod("closeMenu").invoke(chat);});Thread.sleep(600);check(field(chat,"scrimPopupWindow")==null,"Dismiss left an active iOS popup");
                save("ios-message-dismiss-"+(dark?"dark":"light")+"-"+(nativeBlur?"native":"snapshot")+".png");
            }
            runMain(()->config.getClass().getMethod("setBlurMessageMenuBackground",boolean.class).invoke(config,false));
            runMain(()->{
                android.view.View root=(android.view.View)type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getFragmentView").invoke(chat);android.view.View cell=messageCell(root,507);java.lang.reflect.Method menu=chatType.getDeclaredMethod("createMenu",android.view.View.class,boolean.class,boolean.class,float.class,float.class,boolean.class,boolean.class,boolean.class);menu.setAccessible(true);menu.invoke(chat,cell,true,false,30f,30f,true,true,false);
            });Thread.sleep(500);Object popup=field(chat,"scrimPopupWindow");check(popup!=null,"Standard menu stopped opening");android.view.View popupRoot=(android.view.View)popup.getClass().getMethod("getContentView").invoke(popup);check(popupRoot.findViewWithTag("calamus_ios_message_preview")==null,"Disabling iOS menu did not restore standard menu");runMain(()->chatType.getMethod("closeMenu").invoke(chat));
        }finally{runMain(()->{chatType.getMethod("closeMenu").invoke(chat);enabledReactions.clear();enabledReactions.addAll(savedReactions);reactionMap.clear();reactionMap.putAll(savedReactionMap);config.getClass().getMethod("setBlurMessageMenuBackground",boolean.class).invoke(config,savedMenu);config.getClass().getMethod("setMsgMenuUnifiedScroll",boolean.class).invoke(config,savedUnified);config.getClass().getMethod("setMsgMenuNativeBlur",boolean.class).invoke(config,savedNative);});}
    }

    private void verifyNativeArchivePipeline(String plugin) throws Exception {
        Class<?> centerType=type("org.telegram.messenger.NotificationCenter"),messageType=type("org.telegram.tgnet.TLRPC$Message"),peerType=type("org.telegram.tgnet.TLRPC$Peer"),host=type("ksnxdev.calamus.plugins.TelegramPluginHost");
        Object[] object={null};
        runMain(()->{
            host.getMethod("install",Context.class).invoke(null,context);
            Object from=type("org.telegram.tgnet.TLRPC$TL_peerUser").getConstructor().newInstance();peerType.getField("user_id").setLong(from,42);
            Object to=type("org.telegram.tgnet.TLRPC$TL_peerUser").getConstructor().newInstance();peerType.getField("user_id").setLong(to,123);
            Object message=type("org.telegram.tgnet.TLRPC$TL_message").getConstructor().newInstance();messageType.getField("id").setInt(message,201);messageType.getField("peer_id").set(message,to);messageType.getField("from_id").set(message,from);messageType.getField("message").set(message,"Native received message");messageType.getField("date").setInt(message,(int)(System.currentTimeMillis()/1000));
            object[0]=type("org.telegram.messenger.MessageObject").getConstructor(int.class,messageType,boolean.class,boolean.class).newInstance(0,message,false,false);
            Object center=centerType.getMethod("getInstance",int.class).invoke(null,0);
            centerType.getMethod("postNotificationName",int.class,Object[].class).invoke(center,centerType.getField("didReceiveNewMessages").getInt(null),new Object[]{42L,new java.util.ArrayList<>(java.util.List.of(object[0])),false,0});
        });
        command("list","");
        JSONObject query=archiveQuery(plugin,archiveScope(42).put("message_id",201));
        check(query.optString("error").isEmpty(),query.optString("error"));
        check(query.getJSONObject("result").getJSONArray("rows").length()==1,"Native incoming notification did not archive received message");
        runMain(()->{
            Object message=type("org.telegram.messenger.MessageObject").getField("messageOwner").get(object[0]);messageType.getField("message").set(message,"Native edited message");messageType.getField("edit_date").setInt(message,(int)(System.currentTimeMillis()/1000));
            Object center=centerType.getMethod("getInstance",int.class).invoke(null,0);
            centerType.getMethod("postNotificationName",int.class,Object[].class).invoke(center,centerType.getField("replaceMessagesObjects").getInt(null),new Object[]{42L,new java.util.ArrayList<>(java.util.List.of(object[0]))});
        });
        command("list","");
        check(archiveQuery(plugin,archiveScope(42).put("message_id",201)).getJSONObject("result").getJSONArray("rows").length()==2,"Native edit notification did not archive both versions");
        runMain(()->{
            Object center=centerType.getMethod("getInstance",int.class).invoke(null,0);
            centerType.getMethod("postNotificationName",int.class,Object[].class).invoke(center,centerType.getField("messagesDeleted").getInt(null),new Object[]{new java.util.ArrayList<>(java.util.List.of(201)),0L,false});
        });
        command("list","");
        JSONObject deletedQuery=archiveQuery(plugin,archiveScope(42).put("message_id",201).put("deleted_only",true));
        check(deletedQuery.optString("error").isEmpty(),"Native deletion disabled archive: "+deletedQuery.optString("error")+" "+plugin(command("list",""),plugin).optString("error"));
        check(deletedQuery.getJSONObject("result").getJSONArray("rows").length()==2,"Native deletion notification did not retain both versions");
        for(int i=0;i<130;i++){
            int incoming=300+i;
            runMain(()->{
                Object message=type("org.telegram.messenger.MessageObject").getField("messageOwner").get(object[0]);messageType.getField("id").setInt(message,incoming);messageType.getField("message").set(message,"Burst message "+incoming);
                Object center=centerType.getMethod("getInstance",int.class).invoke(null,0);
                centerType.getMethod("postNotificationName",int.class,Object[].class).invoke(center,centerType.getField("didReceiveNewMessages").getInt(null),new Object[]{42L,new java.util.ArrayList<>(java.util.List.of(object[0])),false,0});
            });
            JSONObject state=plugin(command("list",""),plugin);
            check(state.getBoolean("enabled"),"Incoming messages stopped archive: "+state.optString("error"));
        }
        JSONObject burstQuery=archiveQuery(plugin,archiveScope(42).put("message_id",429));
        check(burstQuery.optString("error").isEmpty(),burstQuery.optString("error"));
        check(burstQuery.getJSONObject("result").getJSONArray("rows").length()==1,"Latest incoming message was not archived after a burst");
    }

}
