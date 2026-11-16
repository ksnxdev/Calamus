package ksnxdev.calamus.betatest;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BetaInstrumentation extends Instrumentation {
    private Context context;
    private Object manager;
    private Class<?> managerType, callbackType;
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private JSONObject command(String operation, String argument) throws Exception {
        CountDownLatch done = new CountDownLatch(1);JSONObject[] result = {null};
        Object callback = Proxy.newProxyInstance(context.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getName().equals("complete")) { result[0] = (JSONObject) args[0];done.countDown(); }return null;
        });
        managerType.getMethod("execute", String.class, String.class, callbackType).invoke(manager, operation, argument, callback);
        check(done.await(45, TimeUnit.SECONDS), "Plugin operation timed out: " + operation);return result[0];
    }
    private JSONObject record(JSONObject response, String id) throws Exception {
        check(response.optString("error").isEmpty(), response.optString("error"));JSONArray values = response.getJSONArray("plugins");
        for (int i = 0; i < values.length(); i++) if (id.equals(values.getJSONObject(i).getString("id"))) return values.getJSONObject(i);
        throw new AssertionError("Missing plugin " + id);
    }
    private void fixture(String id, String source, boolean storage) throws Exception {
        File file = new File(context.getCacheDir(), id + ".plug");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            JSONObject info = new JSONObject().put("id", id).put("name", "Beta isolation probe").put("version", "1.0").put("description", "Local beta isolation check").put("author", "ksnxdev").put("api_version", 2).put("permissions", new JSONArray(storage ? "[\"storage\"]" : "[]"));
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(info.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("main.py"));zip.write(source.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        JSONObject installed = command("install", file.getAbsolutePath());check(installed.optString("error").isEmpty(), "Fixture install failed: " + installed.optString("error"));file.delete();
        JSONObject grant = new JSONObject().put("plugin_id", id).put("permissions", new JSONArray(storage ? "[\"storage\"]" : "[]")).put("scopes", new JSONArray());
        check(command("grant", grant.toString()).optString("error").isEmpty(), "Fixture permissions failed");
    }
    private void save(String name, Bitmap bitmap) throws Exception {
        File output = new File(context.getExternalFilesDir(null), name);
        try (FileOutputStream stream = new FileOutputStream(output)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
    }
    private void night(boolean enabled) throws Exception {
        try (java.io.InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("cmd uimode night " + (enabled ? "yes" : "no")))) { byte[] data = new byte[1024];while (stream.read(data) != -1) {} }
        Thread.sleep(1600);
    }
    private void present(Activity activity, String className) throws Exception {
        Class<?> kind = Class.forName(className, true, context.getClassLoader());Object fragment = kind.getConstructor().newInstance();
        java.lang.reflect.Method target = null;
        for (java.lang.reflect.Method method : activity.getClass().getMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (args.length == 3 && args[0].isAssignableFrom(kind) && args[1] == boolean.class && args[2] == boolean.class && method.getReturnType() == boolean.class) { target = method;break; }
        }
        check(target != null, "Fragment presentation unavailable after R8");java.lang.reflect.Method presenter = target;
        Throwable[] failure = {null};runOnMainSync(() -> { try { presenter.invoke(activity, fragment, false, true); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new RuntimeException(failure[0]);Thread.sleep(700);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext();
            check((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Public beta is debuggable");
            check(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName.equals("0.1.0-beta.1"), "Wrong beta version");
            check("Calamus".contentEquals(context.getApplicationInfo().loadLabel(context.getPackageManager())), "Wrong application name");
            ActivityMonitor monitor = addMonitor("org.telegram.ui.LaunchActivity", null, false);
            context.startActivity(new Intent().setClassName("ksnxdev.calamus", "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity activity = monitor.waitForActivityWithTimeout(15000);removeMonitor(monitor);check(activity != null, "Launch activity did not start");Thread.sleep(2000);
            for (boolean dark : new boolean[]{false, true}) {
                night(dark);Thread.sleep(500);
                check(!getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Powered by Cherrygram").isEmpty(), "Intro credit missing");
                save("beta-intro-" + (dark ? "dark" : "light") + ".png", getUiAutomation().takeScreenshot());
            }
            Drawable icon = context.getPackageManager().getActivityIcon(new ComponentName(context.getPackageName(), context.getPackageName() + ".CG_Icon_Cherry"));
            check(icon instanceof AdaptiveIconDrawable, "Default launcher icon is not adaptive");
            Bitmap rendered = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);icon.setBounds(0, 0, 256, 256);icon.draw(new Canvas(rendered));
            int violet = 0, pink = 0;
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) { int color = rendered.getPixel(x, y);int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;if (r > 90 && b > g + 25) violet++;if (r > 180 && b > 160 && g < 170) pink++; }
            check(violet > 10000 && pink > 500, "Provided icon colors missing or artwork clipped");save("beta-icon-render.png", rendered);
            runOnMainSync(() -> {
                Dialog dialog = new Dialog(activity);LinearLayout layout = new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(40, 40, 40, 40);
                TextView title = new TextView(activity);title.setText("Calamus 0.1.0-beta.1");title.setTextSize(22);layout.addView(title);
                ImageView preview = new ImageView(activity);preview.setImageDrawable(icon);layout.addView(preview, new LinearLayout.LayoutParams(400, 400));dialog.setContentView(layout);dialog.show();
                layout.setTag(dialog);
            });Thread.sleep(500);save("beta-icon-preview.png", getUiAutomation().takeScreenshot());
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(400);
            for (boolean dark : new boolean[]{false, true}) {
                night(dark);present(activity, "uz.unnarsx.cherrygram.preferences.AboutPreferencesEntry");
                check(!getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Calamus 0.1.0-beta.1").isEmpty(), "About screen does not show beta version");
                save("beta-about-" + (dark ? "dark" : "light") + ".png", getUiAutomation().takeScreenshot());
                present(activity, "ksnxdev.calamus.CalamusPrivacyActivity");
                check(!getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Информация об обработке данных").isEmpty(), "Offline Calamus privacy notice unavailable");
                save("beta-privacy-" + (dark ? "dark" : "light") + ".png", getUiAutomation().takeScreenshot());
                sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(300);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(300);
            }
            ClassLoader loader = context.getClassLoader();managerType = Class.forName("ksnxdev.calamus.plugins.PluginManager", true, loader);callbackType = Class.forName("ksnxdev.calamus.plugins.PluginManager$Callback", true, loader);
            manager = managerType.getMethod("getInstance", Context.class).invoke(null, context);check(command("list", "").getBoolean("isolation_ready"), "Embedded runtime unavailable after R8");
            String id = "ksnxdev.beta_probe";
            File sentinel = new File(context.getFilesDir(), "beta-release-sentinel.txt");try (FileOutputStream stream = new FileOutputStream(sentinel)) { stream.write("beta-probe".getBytes(StandardCharsets.UTF_8)); }
            try {
                fixture(id, PROBE, true);JSONObject loaded = record(command("enable", id), id);check(loaded.getBoolean("enabled"), "R8 Python startup failed: " + loaded.optString("error"));
                File data = new File(context.getNoBackupFilesDir(), "isolated_plugins/data/" + id + ".json");for (int i = 0; i < 100 && !data.exists(); i++) Thread.sleep(100);
                JSONObject values = new JSONObject(new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8));
                for (String key : new String[]{"files", "network", "java", "native", "send"}) check(values.getBoolean(key), "R8 isolation failed: " + key);
                int uid = values.getInt("uid");check(uid != android.os.Process.myUid() && uid % 100000 >= 90000, "Python shares application UID");
                check(context.checkPermission("android.permission.INTERNET", values.getInt("pid"), uid) == PackageManager.PERMISSION_DENIED, "Python inherited INTERNET");
                check(values.getString("unicode").equals("Привет 👋"), "Unicode storage bridge failed");
            } finally { command("remove", id);sentinel.delete(); }
            String hang = "ksnxdev.beta_hang";
            try { fixture(hang, "from calamus_api import Plugin as Base\nclass Plugin(Base):\n def on_load(self,c):\n  while True: pass\n", false);JSONObject stopped = record(command("enable", hang), hang);check(!stopped.getBoolean("enabled") && !stopped.optString("error").isEmpty(), "CPU watchdog failed after R8"); } finally { command("remove", hang); }
            result.putString("stream", "Beta checks passed: signed non-debuggable R8 startup, light/dark intro, supplied adaptive icon, isolated native Python, Unicode storage, denied files/network/Java/native/send and CPU watchdog. No authenticated Telegram account used.\n");finish(-1, result);
        } catch (Throwable error) { result.putString("stream", android.util.Log.getStackTraceString(error));finish(0, result); }
    }
    private static final String PROBE = """
from calamus_api import Plugin as Base
import os,json,_calamus
class Plugin(Base):
 def on_load(self,c):
  for key,run in [('files',lambda: open('/data/user/0/ksnxdev.calamus/files/beta-release-sentinel.txt').read()),('network',lambda: __import__('socket').socket()),('java',lambda: __import__('java')),('native',lambda: __import__('ctypes').CDLL('libc.so'))]:
   try: run(); blocked=False
   except Exception: blocked=True
   c.set(key,blocked)
  response=json.loads(_calamus.request(json.dumps({'operation':'send_message','arguments':{'account_id':0,'user_id':123,'dialog_id':42,'text':'never send'}})))
  c.set('send',bool(response.get('error')))
  c.set('uid',os.getuid())
  c.set('pid',os.getpid())
  c.set('unicode','Привет 👋')
""";
}
