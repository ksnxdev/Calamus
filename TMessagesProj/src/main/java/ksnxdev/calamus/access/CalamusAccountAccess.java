package ksnxdev.calamus.access;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CalamusAccountAccess implements Application.ActivityLifecycleCallbacks {
    private static final long REFRESH_INTERVAL = 15 * 60 * 1000L;
    private static final long RETRY_INTERVAL = 60 * 1000L;
    private static volatile String sourceUrl = BuildConfig.CALAMUS_BLOCKLIST_URL;
    private static volatile Set<Long> blocked = Collections.emptySet();
    private static volatile long lastAttempt, lastSuccess;
    private static volatile boolean loaded, installed;
    private static volatile String error = "";
    private static final AtomicBoolean updating = new AtomicBoolean();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "CalamusAccessList"));
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static final Runnable timer = () -> { if (foreground.get() != null) { refresh(false); schedule(); } };

    private CalamusAccountAccess() {}
    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        initialize(context);
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(new CalamusAccountAccess());
        refresh(false);
    }
    private static AtomicFile cache(Context context) { return new AtomicFile(new File(context.getNoBackupFilesDir(), "calamus-access-list.json")); }
    public static synchronized void initialize(Context context) {
        if (loaded) return;
        loaded = true;
        if (sourceUrl.isEmpty()) return;
        try (InputStream input = cache(context).openRead()) {
            JSONObject stored = new JSONObject(readText(input, AccessList.MAX_BYTES * 2));
            if (!sourceUrl.equals(stored.getString("source"))) return;
            blocked = AccessList.parse(stored.getString("list"));
            lastSuccess = Math.min(System.currentTimeMillis(), stored.optLong("checked"));
        } catch (Exception ignored) { }
    }
    public static boolean isUserBlocked(long user) { return !sourceUrl.isEmpty() && user > 0 && blocked.contains(user); }
    public static boolean isBlocked(int account) { return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && isUserBlocked(UserConfig.getInstance(account).getClientUserId()); }
    public static boolean updating() { return updating.get(); }
    public static boolean hasError() { return !error.isEmpty(); }
    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }
    public static void refresh(boolean force) {
        if (sourceUrl.isEmpty()) return;
        long now = System.currentTimeMillis();
        long interval = error.isEmpty() ? REFRESH_INTERVAL : RETRY_INTERVAL;
        if (!force && now - lastAttempt >= 0 && now - lastAttempt < interval || !updating.compareAndSet(false, true)) return;
        lastAttempt = now; changed(false);
        worker.execute(() -> {
            try { applyDownloaded(download(AccessList.source(sourceUrl)), true); error = ""; }
            catch (Exception failure) { error = "refresh_failed"; }
            finally { updating.set(false); changed(false); }
        });
    }
    private static String readText(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int length;
        while ((length = input.read(buffer)) != -1) {
            if (output.size() + length > maximum) throw new IOException("Access list is too large");
            output.write(buffer, 0, length);
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }
    private static String download(URI uri) throws Exception {
        for (int redirects = 0; redirects < 4; redirects++) {
            HttpURLConnection connection = uz.unnarsx.cherrygram.donates.SSLUtils.INSTANCE.openSecureConnection(uri.toURL());
            if (connection == null) throw new IOException("HTTPS is required");
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/plain"); connection.setRequestProperty("User-Agent", "Calamus/" + BuildConfig.CALAMUS_VERSION_NAME);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location"); if (location == null) throw new IOException("Invalid redirect");
                    URI next = uri.resolve(location);
                    if (!"https".equalsIgnoreCase(next.getScheme()) || next.getUserInfo() != null || !uri.getHost().equalsIgnoreCase(next.getHost())) throw new IOException("Unexpected access list redirect");
                    uri = next; continue;
                }
                return readResponse(connection);
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many access list redirects");
    }
    private static String readResponse(HttpURLConnection connection) throws Exception {
        if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > AccessList.MAX_BYTES) throw new IOException("Access list unavailable");
        String type = connection.getContentType();
        if (type != null && (type.toLowerCase(java.util.Locale.ROOT).contains("html") || type.toLowerCase(java.util.Locale.ROOT).contains("json"))) throw new IOException("Expected a plain text access list");
        try (InputStream input = connection.getInputStream()) {
            String text = readText(input, AccessList.MAX_BYTES); AccessList.parse(text); return text;
        }
    }
    private static void applyDownloaded(String text, boolean persist) throws Exception {
        Set<Long> next = AccessList.parse(text);
        if (persist) {
            StringBuilder normalized = new StringBuilder(); for (long id : next) normalized.append(id).append('\n');
            byte[] data = new JSONObject().put("source", sourceUrl).put("checked", System.currentTimeMillis()).put("list", normalized.toString()).toString().getBytes(StandardCharsets.UTF_8);
            AtomicFile file = cache(ApplicationLoader.applicationContext); FileOutputStream output = null;
            try { output = file.startWrite(); output.write(data); file.finishWrite(output); }
            catch (Exception failure) { if (output != null) file.failWrite(output); throw failure; }
        }
        boolean accessChanged = !blocked.equals(next);
        blocked = next; lastSuccess = System.currentTimeMillis(); changed(true);
        if (accessChanged) ksnxdev.calamus.plugins.PluginBridge.archiveChanged(-1, 0);
    }
    public static void accountChanged() { changed(true); }
    public static void checkAccess() { main.post(CalamusAccountAccess::checkForeground); }
    public static boolean requiresUnlock() { return !org.telegram.messenger.SharedConfig.passcodeHash.isEmpty() && (org.telegram.messenger.SharedConfig.appLocked || org.telegram.messenger.AndroidUtilities.needShowPasscode()); }
    private static void changed(boolean enforce) {
        main.post(() -> {
            if (enforce) {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated() && isBlocked(a)) org.telegram.messenger.NotificationsController.getInstance(a).refreshAccountAccess();
                checkForeground();
            }
            for (Runnable listener : listeners) listener.run();
        });
    }
    private static void schedule() { main.removeCallbacks(timer); if (!sourceUrl.isEmpty()) main.postDelayed(timer, RETRY_INTERVAL); }
    private static void checkForeground() {
        Activity activity = foreground.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity instanceof RestrictedAccountActivity || !isBlocked(UserConfig.selectedAccount) || requiresUnlock()) return;
        if (activity instanceof LaunchActivity) {
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (fragment instanceof LoginActivity && !UserConfig.getInstance(fragment.getCurrentAccount()).isClientActivated()) return;
        }
        activity.startActivity(new Intent(activity, RestrictedAccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
    @Override public void onActivityResumed(Activity activity) { foreground = new WeakReference<>(activity); refresh(false); checkForeground(); schedule(); }
    @Override public void onActivityPaused(Activity activity) { if (foreground.get() == activity) foreground.clear(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) { if (foreground.get() == activity) foreground.clear(); }
}
