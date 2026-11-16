package ksnxdev.calamus.plugins;

import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

final class PluginTransport {
    interface Listener {
        void api(String session, String request, PluginBridge.Result reply);
        void fault(String session, String error);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Link> links = new HashMap<>();
    private int next;
    private String source;
    private String nativeModules;
    private final class Link {
        final String token;
        final Messenger receiver;
        final Map<Integer, Pending> pending = new HashMap<>();
        ServiceConnection connection;
        Messenger remote;
        boolean bound;
        int uid = -1;
        Link(String token) { this.token = token; receiver = new Messenger(new Handler(Looper.getMainLooper(), message -> receive(this, message))); }
    }
    private static final class Pending {
        int id;
        JSONObject packet;
        final CountDownLatch done = new CountDownLatch(1);
        String error;
        Runnable timeout;
    }
    PluginTransport(Context context, Listener listener) { this.context = context.getApplicationContext(); this.listener = listener; }
    private boolean receive(Link link, Message message) {
        if (links.get(link.token) != link || !PluginRuntimeModule.isIsolatedUid(message.sendingUid)) return true;
        if (link.uid == -1) link.uid = message.sendingUid;
        if (link.uid != message.sendingUid) return true;
        if (message.what == 20) {
            Messenger target = message.replyTo; int id = message.arg1;
            listener.api(link.token, message.getData().getString("request", "{}"), (value, error) -> {
                try {
                    String json = IsolatedPluginService.result(value, error);
                    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144) json = IsolatedPluginService.result(null, "API response exceeds size limit");
                    Message reply = Message.obtain(null, 21, id, 0); Bundle data = new Bundle(); data.putString("result", json); reply.setData(data); target.send(reply);
                } catch (Exception ignored) { }
            });
        } else if (message.what == 3) {
            String error = message.getData().getString("error", "");
            if (!message.getData().getBoolean("isolated") || message.getData().getInt("host_uid") != link.uid) { fail(link, "OS process isolation could not be verified"); return true; }
            Pending request = link.pending.remove(message.arg1);
            if (request != null) { main.removeCallbacks(request.timeout); request.error = error; request.done.countDown(); }
            if (!error.isEmpty()) fail(link, error);
        }
        return true;
    }
    void request(JSONObject packet) throws Exception {
        Pending request = new Pending(); request.packet = packet;
        main.post(() -> {
            String token = packet.optString("session");
            Link link = links.get(token);
            boolean fresh = link == null;
            if (fresh) { link = new Link(token); links.put(token, link); }
            Link channel = link;
            request.id = ++next;
            request.timeout = () -> fail(channel, "Plugin execution timed out");
            link.pending.put(request.id, request); main.postDelayed(request.timeout, 60000);
            if (link.remote != null) send(link, request);
            else if (fresh) bind(link);
        });
        if (!request.done.await(65, TimeUnit.SECONDS)) throw new IllegalStateException("Isolated plugin did not respond");
        if (request.error != null && !request.error.isEmpty()) throw new IllegalStateException(request.error);
    }
    void stop(String token) { main.post(() -> { Link link = links.get(token); if (link != null) fail(link, "Plugin stopped"); }); }
    private void bind(Link link) {
        if (!PluginRuntimeModule.ready(context)) { fail(link, "OS-isolated plugins require Android 10 or newer"); return; }
        link.connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (links.get(link.token) != link) return;
                link.remote = new Messenger(binder);
                for (Pending request : new ArrayList<>(link.pending.values())) send(link, request);
            }
            @Override public void onServiceDisconnected(ComponentName name) { fail(link, "Isolated plugin process stopped"); }
            @Override public void onBindingDied(ComponentName name) { fail(link, "Isolated plugin process disconnected"); }
            @Override public void onNullBinding(ComponentName name) { fail(link, "Isolated plugin process unavailable"); }
        };
        try {
            Intent intent = new Intent(context, IsolatedPluginService.class);
            link.bound = context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY, link.token.replace("-", ""), context.getMainExecutor(), link.connection);
            if (!link.bound) fail(link, "Could not start an OS-isolated plugin process");
        } catch (Exception error) { fail(link, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }
    private byte[] asset(String name) throws Exception {
        try (InputStream stream = context.getAssets().open("isolated/" + name)) { return PluginStore.bytes(stream, 16 * 1024 * 1024); }
    }
    private ParcelFileDescriptor sealed(byte[] bytes) throws Exception {
        int fd = NativePython.sealedData(bytes);
        if (fd < 0) throw new IOException("Could not prepare sealed plugin data");
        return ParcelFileDescriptor.adoptFd(fd);
    }
    private void send(Link link, Pending request) {
        ParcelFileDescriptor code = null;
        try {
            JSONObject packet = new JSONObject(request.packet.toString());
            Message message = Message.obtain(null, 1, request.id, 0); Bundle data = new Bundle();
            if (packet.optString("operation").equals("load")) {
                if (source == null) { source = new String(asset("worker.py"), java.nio.charset.StandardCharsets.UTF_8); nativeModules = new String(asset("native_modules.json"), java.nio.charset.StandardCharsets.UTF_8); }
                try (InputStream stream = new FileInputStream(packet.getString("archive"))) { code = sealed(PluginStore.bytes(stream, 2 * 1024 * 1024)); }
                data.putParcelable("package", code);
                packet.remove("archive"); packet.put("source", source).put("native_modules", nativeModules);
            }
            data.putString("request", packet.toString()); message.setData(data); message.replyTo = link.receiver; link.remote.send(message);
        } catch (Throwable error) { fail(link, "Isolated runtime initialization failed: " + error.getClass().getSimpleName() + ": " + error.getMessage()); }
        finally { if (code != null) try { code.close(); } catch (Exception ignored) { } }
    }
    private void fail(Link link, String error) {
        if (!links.remove(link.token, link)) return;
        if (link.bound) try { context.unbindService(link.connection); } catch (Exception ignored) { }
        for (Pending request : link.pending.values()) { main.removeCallbacks(request.timeout); request.error = error; request.done.countDown(); }
        link.pending.clear(); listener.fault(link.token, error);
    }
}
