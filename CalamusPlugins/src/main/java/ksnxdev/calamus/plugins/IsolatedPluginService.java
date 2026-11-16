package ksnxdev.calamus.plugins;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class IsolatedPluginService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, ApiCall> calls = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger owner;
    private String session;
    private ParcelFileDescriptor archive;
    private volatile boolean closed;
    private volatile boolean busy;
    private volatile long operationStarted;
    private volatile long apiStarted;
    private long rateStart;
    private int requests;
    private int pending;
    private boolean loaded;
    private Invocation queued;

    private static final class Invocation {
        final int request;
        final String operation;
        final String argument;
        Invocation(int request, String operation, String argument) { this.request = request; this.operation = operation; this.argument = argument; }
    }

    private static final class ApiCall {
        final CountDownLatch done = new CountDownLatch(1);
        String response;
    }
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < 29 || !android.os.Process.isIsolated()) throw new SecurityException("An OS-isolated process is required");
        main.postDelayed(this::watchdog, 1000);
    }
    @Override public IBinder onBind(Intent intent) { return receiver.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { shutdown("Plugin disconnected"); return false; }
    @Override public void onDestroy() { shutdown("Plugin stopped"); super.onDestroy(); }

    private boolean receive(Message message) {
        if (message.sendingUid != getApplicationInfo().uid || closed) return true;
        if (message.what == 21) {
            ApiCall call = calls.remove(message.arg1);
            if (call != null) { call.response = message.getData().getString("result", "{}"); call.done.countDown(); }
            return true;
        }
        if (message.what != 1 || message.replyTo == null) return true;
        try {
            JSONObject packet = new JSONObject(message.getData().getString("request", "{}"));
            String operation = packet.getString("operation");
            if (operation.equals("load")) {
                if (owner != null || busy) throw new SecurityException("A process can host only one plugin");
                owner = message.replyTo; session = packet.getString("session");
                archive = message.getData().getParcelable("package");
                if (archive == null) throw new SecurityException("A sealed package descriptor is required");
                JSONObject config = new JSONObject().put("id", packet.getString("id"))
                        .put("permissions", packet.getJSONArray("permissions")).put("native_dir", getApplicationInfo().nativeLibraryDir).put("native_modules", new JSONObject(packet.getString("native_modules")));
                String source = packet.getString("source");
                begin(message.arg1);
                worker.execute(() -> {
                    String error;
                    try {
                        byte[] code;
                        try (java.io.InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(archive)) { code = PluginStore.bytes(stream, 2 * 1024 * 1024); }
                        error = NativePython.initialize(this, getApplicationInfo().sourceDir + "/assets/isolated/python", code, source, config.toString());
                        if (error.isEmpty()) error = NativePython.dispatch("load", "{}");
                    }
                    catch (Throwable failure) { error = failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
                    String result = error;
                    main.post(() -> { loaded = result.isEmpty(); complete(result); });
                });
            } else {
                if (owner == null || !session.equals(packet.optString("session"))) throw new SecurityException("Unknown session");
                if (operation.equals("stop")) { shutdown("Plugin stopped"); return true; }
                if (!loaded) throw new IllegalStateException("Isolated worker is not ready");
                if (busy) {
                    if (pending != 0 || queued != null) throw new IllegalStateException("Isolated worker is busy");
                    queued = new Invocation(message.arg1, operation, packet.optString("argument", "{}"));
                    return true;
                }
                begin(message.arg1);
                invoke(operation, packet.optString("argument", "{}"));
            }
        } catch (Exception error) {
            send(message.replyTo, message.arg1, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        return true;
    }
    private void begin(int request) { busy = true; pending = request; operationStarted = SystemClock.elapsedRealtime(); }
    private void invoke(String operation, String argument) {
        worker.execute(() -> {
            String error;
            try { error = NativePython.dispatch(operation, argument); }
            catch (Throwable failure) { error = failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
            String result = error; main.post(() -> complete(result));
        });
    }
    private void complete(String error) {
        if (closed) return;
        int request = pending; pending = 0; busy = false;
        if (request != 0 || !error.isEmpty()) send(owner, request, error);
        if (!error.isEmpty()) { shutdown(error); return; }
        if (queued != null) {
            Invocation next = queued; queued = null;
            begin(next.request);
            invoke(next.operation, next.argument);
        }
    }
    public String request(String packet) {
        if (closed) return result(null, "Isolated worker stopped");
        try {
            PluginJson.validate(packet, 65536);
            synchronized (this) {
                long now = SystemClock.elapsedRealtime();
                if (now - rateStart > 60000) { rateStart = now; requests = 0; }
                if (++requests > 240) throw new SecurityException("Plugin API rate limit exceeded");
            }
            int id = sequence.incrementAndGet(); ApiCall call = new ApiCall(); calls.put(id, call); apiStarted = SystemClock.elapsedRealtime();
            try {
                Message message = Message.obtain(null, 20, id, 0); Bundle data = new Bundle(); data.putString("request", packet);
                message.setData(data); message.replyTo = receiver; owner.send(message);
                if (!call.done.await(30, TimeUnit.SECONDS)) return result(null, "Plugin API timed out");
                return call.response;
            } finally { calls.remove(id); operationStarted += SystemClock.elapsedRealtime() - apiStarted; apiStarted = 0; }
        } catch (Exception error) { return result(null, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }
    private void watchdog() {
        if (closed) return;
        long now = SystemClock.elapsedRealtime(); long waiting = apiStarted == 0 ? 0 : now - apiStarted;
        if (busy && now - operationStarted > 15000 && !(waiting > 1000 && waiting < 30000)) { shutdown("Plugin exceeded its execution time limit"); return; }
        if (loaded && !busy) { begin(0); invoke("tick", "{}"); }
        main.postDelayed(this::watchdog, 250);
    }
    private void shutdown(String error) {
        if (closed) return;
        closed = true;
        send(owner, pending, error); worker.shutdownNow();
        if (android.os.Process.isIsolated()) android.os.Process.killProcess(android.os.Process.myPid());
    }
    private void send(Messenger target, int id, String error) {
        if (target == null) return;
        try {
            Message message = Message.obtain(null, 3, id, 0); Bundle data = new Bundle(); data.putString("error", error == null ? "" : error);
            data.putBoolean("isolated", android.os.Process.isIsolated()); data.putInt("host_uid", android.os.Process.myUid()); data.putInt("host_pid", android.os.Process.myPid());
            message.setData(data); target.send(message);
        } catch (Exception ignored) { }
    }
    static String result(Object value, String error) {
        try { return new JSONObject().put("result", value == null ? JSONObject.NULL : value).put("error", error == null ? "" : error).toString(); }
        catch (Exception failure) { return "{\"error\":\"Invalid response\"}"; }
    }
}
