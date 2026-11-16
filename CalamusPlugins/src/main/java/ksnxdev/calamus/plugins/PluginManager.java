package ksnxdev.calamus.plugins;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class PluginManager implements PluginTransport.Listener {
    public interface Callback { void complete(JSONObject result); }
    private static PluginManager instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> new Thread(task, "CalamusPluginControl"));
    private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();
    private final PluginTransport transport;
    private volatile PluginStore store;
    private volatile JSONArray registeredCommands = new JSONArray();
    private volatile JSONArray registeredMessageActions = new JSONArray();
    private final ThreadPoolExecutor archiveWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> new Thread(task, "CalamusPluginArchive"));
    private final LinkedHashMap<String, InstallReview> reviews = new LinkedHashMap<>();
    private static final class InstallReview {
        final File file;
        final JSONObject manifest;
        final String token = UUID.randomUUID().toString();
        final String digest;
        final String previous;
        final long expires = android.os.SystemClock.elapsedRealtime() + 15 * 60 * 1000;
        InstallReview(File file, JSONObject manifest, String digest, String previous) { this.file = file; this.manifest = manifest; this.digest = digest; this.previous = previous; }
    }
    private static final class ReviewException extends Exception {
        final String code;
        ReviewException(String code, String text) { super(text); this.code = code; }
    }
    private final Map<String, Long> noticeTimes = new HashMap<>();
    private final Map<String, Long> requestTimes = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> budgets = new HashMap<>();
    private final Map<String, Long> interactionUntil = new ConcurrentHashMap<>();

    private PluginManager(Context context) {
        this.context = context.getApplicationContext(); transport = new PluginTransport(context, this);
        File[] abandoned = this.context.getCacheDir().listFiles((directory, name) -> name.startsWith("calamus-review-") && name.endsWith(".plug"));
        if (abandoned != null) for (File file : abandoned) file.delete();
    }
    public static synchronized PluginManager getInstance(Context context) { if (instance == null) instance = new PluginManager(context); return instance; }
    private PluginStore store() throws Exception { if (store == null) store = new PluginStore(context); return store; }
    public void start() { execute("resume", "", null); }
    public JSONArray commands() { try { return new JSONArray(registeredCommands.toString()); } catch (Exception error) { return new JSONArray(); } }
    public JSONArray commandsForChat(JSONObject chat) {
        JSONArray result = new JSONArray();
        if (store == null) return result;
        JSONArray list = commands();
        for (int i = 0; i < list.length(); i++) try {
            JSONObject command = list.getJSONObject(i); JSONObject record = store.record(command.getString("plugin_id"));
            if (record.optBoolean("enabled") && PluginAccessPolicy.scope(record, chat, true)) result.put(command);
        } catch (Exception ignored) { }
        return result;
    }
    public boolean dispatchCommand(String text, JSONObject chat) {
        String value = text.trim(); if (!value.startsWith(".") || value.length() > 4096) return false;
        String[] parts = value.substring(1).split("\\s+", 2); JSONArray commands = commandsForChat(chat);
        for (int i = 0; i < commands.length(); i++) if (commands.optJSONObject(i).optString("name").equals(parts[0])) return runCommand(commands.optJSONObject(i), parts.length > 1 ? parts[1] : "", chat, null);
        return false;
    }
    public boolean runCommand(JSONObject command, String arguments, JSONObject chat, Callback callback) {
        try { return submit("command", new JSONObject().put("plugin_id", command.getString("plugin_id")).put("name", command.getString("name")).put("arguments", arguments).put("chat", chat).toString(), callback); }
        catch (Exception error) { return false; }
    }
    public JSONArray messageActionsForChat(JSONObject chat) {
        JSONArray result = new JSONArray();
        if (store == null) return result;
        for (int i = 0; i < registeredMessageActions.length(); i++) try {
            JSONObject action = registeredMessageActions.getJSONObject(i); String id = action.getString("plugin_id"); JSONObject record = store.record(id);
            if (sessions.containsKey(id) && record.optBoolean("enabled") && PluginAccessPolicy.has(record, "messages.read") && PluginAccessPolicy.has(record, "messages.menu") && PluginAccessPolicy.scope(record, chat, true)) result.put(new JSONObject(action.toString()));
        } catch (Exception ignored) { }
        return result;
    }
    public boolean runMessageAction(JSONObject action, JSONObject chat, JSONObject message) {
        try { return submit("message_action", new JSONObject().put("plugin_id", action.getString("plugin_id")).put("name", action.getString("name")).put("chat", chat).put("message", message).toString(), null); }
        catch (Exception error) { return false; }
    }
    public void archiveRequest(String id, String session, String operation, JSONObject args, PluginBridge.Result callback) {
        try {
            JSONObject copy = new JSONObject(args.toString());
            archiveWorker.execute(() -> {
                try {
                    if (!authorizeApiNow(id, session, operation, copy)) throw new SecurityException("Plugin access was revoked");
                    JSONObject result;
                    synchronized (PluginArchiveStore.class) {
                        if (!authorizeApiNow(id, session, operation, copy)) throw new SecurityException("Plugin access was revoked");
                        result = PluginArchiveStore.request(context, id, operation, copy);
                    }
                    if (!authorizeApiNow(id, session, operation, copy)) throw new SecurityException("Plugin access was revoked");
                    main.post(() -> callback.complete(result, ""));
                } catch (Exception error) { main.post(() -> callback.complete(null, error.getClass().getSimpleName() + ": " + error.getMessage())); }
            });
        } catch (Exception error) { callback.complete(null, "Archive queue is full"); }
    }
    public JSONArray archiveProviders(JSONObject chat) {
        JSONArray result = new JSONArray();
        if (store == null) return result;
        for (String id : sessions.keySet()) try {
            JSONObject record = store.record(id); String session = sessions.get(id);
            if (session != null && record.optBoolean("enabled") && PluginAccessPolicy.has(record, "messages.archive") && PluginAccessPolicy.has(record, "messages.read") && PluginAccessPolicy.has(record, "ui") && PluginAccessPolicy.scope(record, chat, true)) result.put(new JSONObject().put("plugin", id).put("session", session));
        } catch (Exception ignored) { }
        return result;
    }
    private boolean capturing(String id) {
        try { return !Boolean.FALSE.equals(store().setting(id, "capture")); } catch (Exception error) { return true; }
    }
    public boolean canRetainMessages(JSONObject chat) {
        JSONArray providers = archiveProviders(chat);
        for (int i = 0; i < providers.length(); i++) if (capturing(providers.optJSONObject(i).optString("plugin"))) return true;
        return false;
    }
    public void retainDeleted(JSONObject chat, JSONArray messages) {
        try {
            JSONObject scope = new JSONObject(chat.toString()); JSONArray snapshots = new JSONArray(messages.toString()), providers = archiveProviders(scope);
            archiveWorker.execute(() -> {
                for (int p = 0; p < providers.length(); p++) try {
                    JSONObject provider = providers.getJSONObject(p); String id = provider.getString("plugin"), session = provider.getString("session");
                    if (!capturing(id) || !authorizeApiNow(id, session, "archive.record", scope)) continue;
                    JSONArray ids = new JSONArray();
                    for (int i = 0; i < snapshots.length(); i++) {
                        JSONObject message = snapshots.getJSONObject(i);
                        synchronized (PluginArchiveStore.class) {
                            if (!authorizeApiNow(id, session, "archive.record", scope)) break;
                            PluginArchiveStore.request(context, id, "archive.record", new JSONObject(scope.toString()).put("message", message)); ids.put(message.getInt("id"));
                        }
                    }
                    PluginArchiveStore.deleted(context, id, scope.getLong("user_id"), scope.optLong("channel_id"), ids, new JSONArray().put(scope));
                    PluginBridge.archiveChanged(scope.getInt("account_id"), scope.getLong("dialog_id"));
                } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
    public void deletedSnapshot(JSONObject chat, int minimum, int maximum, Callback callback) {
        try {
            JSONObject scope = new JSONObject(chat.toString()); JSONArray providers = archiveProviders(scope);
            archiveWorker.execute(() -> {
                LinkedHashMap<Integer, JSONObject> found = new LinkedHashMap<>();
                for (int i = 0; i < providers.length(); i++) try {
                    JSONObject provider = providers.getJSONObject(i); String id = provider.getString("plugin"), session = provider.getString("session");
                    if (!authorizeApiNow(id, session, "archive.query", scope)) continue;
                    JSONArray rows = PluginArchiveStore.deletedInRange(context, id, scope.getLong("user_id"), scope.getLong("dialog_id"), minimum, maximum);
                    for (int r = 0; r < rows.length(); r++) { JSONObject row = rows.getJSONObject(r); row.put("_plugin", id).put("_session", session); found.put(row.getInt("message_id"), row); }
                } catch (Exception ignored) { }
                main.post(() -> {
                    JSONArray rows = new JSONArray();
                    for (JSONObject row : found.values()) if (authorizeApiNow(row.optString("_plugin"), row.optString("_session"), "archive.query", scope)) rows.put(row);
                    callback.complete(wrapRows(rows));
                });
            });
        } catch (Exception error) { main.post(() -> callback.complete(wrapRows(new JSONArray()))); }
    }
    private static JSONObject wrapRows(JSONArray rows) { JSONObject value = new JSONObject(); try { value.put("rows", rows); } catch (Exception ignored) { } return value; }
    public void event(String name) { event(name, new JSONObject()); }
    public void event(String name, JSONObject payload) {
        if (sessions.isEmpty() && !name.equals("account.changed")) return;
        submit("event", json(() -> new JSONObject().put("name", name).put("payload", payload)), null);
    }
    private interface JsonValue { JSONObject get() throws Exception; }
    private static String json(JsonValue value) { try { return value.get().toString(); } catch (Exception error) { return "{}"; } }
    public void execute(String operation, String argument, Callback callback) {
        if (operation.equals("disable") || operation.equals("remove") || operation.equals("enable") || operation.equals("grant")) {
            String id = argument;
            if (operation.equals("grant")) try { id = new JSONObject(argument).getString("plugin_id"); } catch (Exception ignored) { }
            revoke(id);
        } else if (operation.equals("disable_all")) for (String id : new ArrayList<>(sessions.keySet())) revoke(id);
        submit(operation, argument, callback);
    }
    private void revoke(String id) { String session = sessions.remove(id); if (session != null) { transport.stop(session); PluginBridge.revoke(id, session); PluginBridge.archiveChanged(-1, 0); } }
    private boolean submit(String operation, String argument, Callback callback) {
        try {
            worker.execute(() -> {
                JSONObject result;
                try { result = operation(operation, argument); }
                catch (Exception error) {
                    try { result = new JSONObject().put("error", error.getClass().getSimpleName() + ": " + error.getMessage()); if (store != null) result.put("plugins", snapshot()); if (error instanceof ReviewException) result.put("error_code", ((ReviewException) error).code); }
                    catch (Exception failure) { result = new JSONObject(); }
                }
                JSONObject delivered = result; if (callback != null) main.post(() -> callback.complete(delivered));
            }); return true;
        } catch (RejectedExecutionException error) { if (callback != null) main.post(() -> callback.complete(failure("Plugin queue is full"))); return false; }
    }
    private JSONObject operation(String operation, String argument) throws Exception {
        PluginStore storage = store();
        switch (operation) {
            case "list": break;
            case "message_action": {
                JSONObject value = new JSONObject(argument); String id = value.getString("plugin_id"); JSONObject record = storage.record(id);
                JSONObject actions = record.optJSONObject("message_actions");
                if (!sessions.containsKey(id) || !record.optBoolean("enabled") || !PluginAccessPolicy.has(record, "messages.menu") || !PluginAccessPolicy.has(record, "messages.read") || actions == null || !actions.has(value.getString("name")) || !PluginAccessPolicy.scope(record, value.getJSONObject("chat"), true)) throw new SecurityException("Message action is unavailable");
                JSONObject payload = new JSONObject(value.getJSONObject("chat").toString()).put("action", value.getString("name")).put("message", value.getJSONObject("message"));
                interactionUntil.put(id, android.os.SystemClock.elapsedRealtime() + 60000);
                invoke(id, "event", new JSONObject().put("name", "message.action").put("payload", payload));
                break;
            }
            case "review_get": return reviewInfo(review(argument));
            case "review_cancel": { InstallReview value = reviews.remove(argument); if (value != null) value.file.delete(); break; }
            case "review_installed": {
                File file = File.createTempFile("calamus-review-", ".plug", context.getCacheDir());
                try {
                    try (InputStream input = new FileInputStream(storage.packageFile(argument)); OutputStream output = new FileOutputStream(file)) { PluginStore.transfer(input, output, 2 * 1024 * 1024); }
                    return prepareReview(file);
                } catch (Exception error) { file.delete(); throw error; }
            }
            case "review_confirm": return confirmReview(new JSONObject(argument));
            case "resume": for (String id : ids()) { JSONObject record = storage.record(id); if (record.optBoolean("resume")) { record.put("resume", false); enable(id); } } deliver(new JSONObject().put("name", "app.started").put("payload", new JSONObject())); break;
            case "enable": enable(argument); break;
            case "disable": disable(argument, ""); break;
            case "disable_all": for (String id : ids()) disable(id, ""); break;
            case "remove": PluginArchiveStore.remove(context, argument); disable(argument, ""); storage.remove(argument); break;
            case "grant": {
                JSONObject value = new JSONObject(argument); String id = value.getString("plugin_id"); disable(id, "");
                PluginAccessPolicy.grant(storage.record(id), value.getJSONArray("permissions"), value.getJSONArray("scopes")); storage.save(); break;
            }
            case "install": {
                JSONObject manifest = PluginStore.inspect(new File(argument)); String id = manifest.getString("id");
                revoke(id); storage.install(new File(argument)); break;
            }
            case "setting": {
                JSONObject value = new JSONObject(argument); String id = value.getString("plugin_id"); String key = value.getString("key");
                storage.setting(id, key, value.get("value"));
                if (sessions.containsKey(id)) invoke(id, "event", new JSONObject().put("name", "settings.changed").put("payload", new JSONObject().put("key", key).put("value", value.get("value"))));
                break;
            }
            case "command": {
                JSONObject value = new JSONObject(argument); String id = value.getString("plugin_id"); JSONObject record = storage.record(id);
                if (!record.optBoolean("enabled") || !PluginAccessPolicy.has(record, "commands") || !record.optJSONObject("commands").has(value.getString("name"))) throw new SecurityException("Command is unavailable");
                if (value.getJSONObject("chat").length() != 0 && !PluginAccessPolicy.scope(record, value.getJSONObject("chat"), true)) throw new SecurityException("Chat access was not granted");
                interactionUntil.put(id, android.os.SystemClock.elapsedRealtime() + 60000);
                invoke(id, "command", value); break;
            }
            case "event": deliver(new JSONObject(argument)); break;
            default: throw new IllegalArgumentException("Unsupported plugin operation");
        }
        publish(); return new JSONObject().put("plugins", snapshot()).put("error", "").put("isolated_runtime", true).put("isolation_ready", PluginRuntimeModule.ready(context));
    }
    private ArrayList<String> ids() throws Exception {
        ArrayList<String> ids = new ArrayList<>(); JSONArray values = store().snapshot(); for (int i = 0; i < values.length(); i++) ids.add(values.getJSONObject(i).getString("id")); return ids;
    }
    private JSONArray snapshot() throws Exception { return store().snapshot(); }
    private void publish() throws Exception {
        JSONArray commands = new JSONArray(); JSONArray actions = new JSONArray();
        for (String id : ids()) {
            JSONObject record = store().record(id); if (!record.optBoolean("enabled")) continue;
            JSONObject menu = record.optJSONObject("message_actions");
            if (menu != null && PluginAccessPolicy.has(record, "messages.menu")) {
                Iterator<String> entries = menu.keys();
                while (entries.hasNext()) { String name = entries.next(); actions.put(new JSONObject().put("plugin_id", id).put("name", name).put("title", menu.getString(name)).put("plugin_name", pluginName(id))); }
            }
            if (!PluginAccessPolicy.has(record, "commands")) continue;
            JSONObject names = record.optJSONObject("commands"); if (names == null) continue;
            Iterator<String> keys = names.keys();
            while (keys.hasNext()) { String name = keys.next(); commands.put(new JSONObject().put("plugin_id", id).put("name", name).put("title", names.getString(name)).put("plugin_name", pluginName(id))); }
        }
        registeredCommands = commands; registeredMessageActions = actions;
    }
    private void enable(String id) throws Exception {
        JSONObject record = store().record(id);
        if (!record.getString("digest").equals(record.optString("grant_digest"))) throw new SecurityException("Review and grant plugin permissions first");
        JSONArray required = record.getJSONObject("manifest").optJSONArray("required_permissions");
        if (required != null) for (int i = 0; i < required.length(); i++) if (!PluginAccessPolicy.has(record, required.getString(i))) throw new SecurityException("Required permission was not granted: " + required.getString(i));
        if (sessions.size() >= 4) throw new IllegalStateException("At most four plugins can run concurrently");
        String session = UUID.randomUUID().toString(); sessions.put(id, session);
        record.put("enabled", true).put("error", "").put("commands", new JSONObject()).put("message_actions", new JSONObject()); store().save();
        try {
            transport.request(new JSONObject().put("operation", "load").put("session", session).put("id", id).put("archive", store().packageFile(id).getAbsolutePath()).put("permissions", record.getJSONArray("permissions")));
        } catch (Exception error) { disable(id, error.getMessage()); }
        store().save();
        PluginBridge.archiveChanged(-1, 0);
    }
    private void disable(String id, String error) throws Exception {
        revoke(id); JSONObject record = store().record(id); record.put("enabled", false).put("commands", new JSONObject()).put("error", error == null ? "" : error); store().save(); publish();
    }
    private void invoke(String id, String operation, JSONObject value) throws Exception {
        String session = sessions.get(id); if (session == null) throw new IllegalStateException("Plugin is disabled");
        try { transport.request(new JSONObject().put("operation", operation).put("session", session).put("argument", value.toString())); }
        catch (Exception error) { disable(id, error.getMessage()); }
    }
    private void deliver(JSONObject event) throws Exception {
        String name = event.getString("name"); JSONObject payload = event.optJSONObject("payload"); if (payload == null) payload = new JSONObject();
        if (!PluginStore.EVENTS.contains(name)) return;
        if (name.equals("account.changed") && !payload.optBoolean("logged_in", true)) {
            long user = payload.optLong("user_id");
            for (String id : ids()) {
                JSONObject record = store().record(id); JSONArray scopes = record.optJSONArray("scopes"); boolean affected = false;
                if (scopes != null) for (int i = 0; i < scopes.length(); i++) affected |= scopes.getJSONObject(i).optLong("user_id") == user;
                if (affected) { disable(id, "Account logged out; review access again"); record.put("scopes", new JSONArray()).remove("grant_digest"); }
                PluginArchiveStore.clearUser(context, id, user);
                JSONObject values = store().values(id, false); Iterator<String> keys = values.keys(); ArrayList<String> remove = new ArrayList<>(); while (keys.hasNext()) { String key = keys.next(); if (key.startsWith("account:" + user + ":")) remove.add(key); } for (String key : remove) values.remove(key);
                PluginStore.write(new File(new File(store().root, "data"), id + ".json"), values);
            }
            store().save(); return;
        }
        for (String id : new ArrayList<>(sessions.keySet())) {
            JSONObject record = store().record(id); JSONArray events = record.getJSONObject("manifest").getJSONArray("events"); boolean subscribed = false;
            for (int i = 0; i < events.length(); i++) subscribed |= name.equals(events.optString(i));
            if (!subscribed) continue;
            if (name.equals("message.deleted")) {
                if (!PluginAccessPolicy.has(record, "messages.archive") || !PluginAccessPolicy.has(record, "messages.read")) continue;
                JSONArray affected = PluginArchiveStore.deleted(context, id, payload.getLong("user_id"), payload.optLong("channel_id"), payload.getJSONArray("ids"), record.getJSONArray("scopes"));
                for (int i = 0; i < affected.length(); i++) {
                    JSONObject scope = affected.getJSONObject(i);
                    PluginBridge.archiveChanged(scope.getInt("account_id"), scope.getLong("dialog_id"));
                    invoke(id, "event", new JSONObject().put("name", name).put("payload", scope));
                }
                continue;
            }
            if (name.startsWith("message.") && (!PluginAccessPolicy.has(record, "messages.read") || payload.optLong("dialog_id") == 0)) continue;
            if (name.equals("chat.opened") && !PluginAccessPolicy.has(record, "chats.read")) continue;
            if (payload.has("account_id") && !PluginAccessPolicy.scope(record, payload, payload.optLong("dialog_id") != 0)) continue;
            invoke(id, "event", event);
        }
    }
    public String pluginName(String id) { try { return store().info(id).getString("name"); } catch (Exception error) { return id; } }
    public boolean authorizeApiNow(String id, String session, String operation, JSONObject args) {
        try { if (!session.equals(sessions.get(id))) return false; PluginAccessPolicy.authorize(store().record(id), operation, args); return true; } catch (Exception error) { return false; }
    }
    @Override public void api(String session, String request, PluginBridge.Result callback) {
        String id = null; for (Map.Entry<String, String> item : sessions.entrySet()) if (item.getValue().equals(session)) { id = item.getKey(); break; }
        if (id == null) { callback.complete(null, "Plugin session was revoked"); return; }
        final String pluginId = id;
        try {
            PluginJson.validate(request, 65536);
            JSONObject packet = new JSONObject(request); String operation = packet.getString("operation"); JSONObject args = packet.getJSONObject("arguments");
            JSONObject record = store().record(id); PluginAccessPolicy.authorize(record, operation, args);
            Object local = null; boolean handled = true;
            switch (operation) {
                case "store.get": local = store().get(id, args.getString("key"), args.opt("default")); break;
                case "store.set": budget(id + ":storage", 60); store().set(id, args.getString("key"), args.get("value")); local = true; break;
                case "setting.get": local = store().setting(id, args.getString("key")); break;
                case "message.action.register": {
                    String name = args.getString("name"), title = args.getString("title");
                    if (!name.matches("[a-z][a-z0-9_]{0,31}") || title.isEmpty() || title.length() > 80) throw new SecurityException("Invalid message action");
                    JSONObject actions = record.optJSONObject("message_actions"); if (actions == null) actions = new JSONObject();
                    if (actions.length() >= 8 && !actions.has(name)) throw new SecurityException("Too many message actions");
                    actions.put(name, title); record.put("message_actions", actions); store().save(); publish(); local = true; break;
                }
                case "command.register": {
                    String name = args.getString("name"); String title = args.getString("title");
                    if (!name.matches("[a-z][a-z0-9_]{0,31}") || title.isEmpty() || title.length() > 80) throw new SecurityException("Invalid command");
                    JSONObject commands = record.optJSONObject("commands"); if (commands == null) commands = new JSONObject();
                    if (commands.length() >= 16 && !commands.has(name)) throw new SecurityException("Too many commands");
                    for (String other : ids()) { if (other.equals(id)) continue; JSONObject existing = store().record(other).optJSONObject("commands"); if (existing != null && existing.has(name)) throw new SecurityException("Command name conflict"); }
                    commands.put(name, title); record.put("commands", commands); local = true; break;
                }
                case "log": {
                    String text = args.getString("text"); if (text.length() > 500) text = text.substring(0, 500);
                    JSONArray logs = record.optJSONArray("logs"); if (logs == null) logs = new JSONArray(); logs.put(text);
                    while (logs.length() > 8) logs.remove(0); record.put("logs", logs); local = true; break;
                }
                case "notify": {
                    long now = android.os.SystemClock.elapsedRealtime(); if (noticeTimes.containsKey(id) && now - noticeTimes.get(id) < 5000) throw new SecurityException("Notification rate limit"); noticeTimes.put(id, now);
                    String text = args.getString("text"); android.widget.Toast.makeText(context, pluginName(id) + ": " + text.substring(0, Math.min(200, text.length())), android.widget.Toast.LENGTH_SHORT).show(); local = true; break;
                }
                default: handled = false;
            }
            if (handled) { callback.complete(local, ""); return; }
            if (operation.equals("history")) rate(id + ":history", 10000);
            if (operation.equals("show_dialog") || operation.equals("open_chat") || operation.equals("archive.show")) {
                if (interactionUntil.getOrDefault(id, 0L) < android.os.SystemClock.elapsedRealtime()) throw new SecurityException("UI actions require a command invoked by the user");
                rate(id + ":ui", 5000);
            }
            args.put("_isolation_session", session);
            PluginBridge.dispatch(id, operation, args, (value, error) -> {
                if (!authorizeApiNow(pluginId, session, operation, args)) { callback.complete(null, "Plugin access was revoked"); return; }
                try {
                    if ((operation.equals("accounts") || operation.equals("chats")) && value instanceof JSONArray) {
                        JSONArray filtered = new JSONArray(); JSONArray all = (JSONArray) value;
                        for (int i = 0; i < all.length(); i++) {
                            JSONObject item = all.getJSONObject(i); JSONObject check = operation.equals("accounts") ? item : new JSONObject(args.toString()).put("dialog_id", item.getLong("dialog_id"));
                            if (PluginAccessPolicy.scope(record, check, operation.equals("chats"))) filtered.put(item);
                        }
                        value = filtered;
                    }
                    callback.complete(value, error);
                } catch (Exception failure) { callback.complete(null, "Client response rejected"); }
            });
        } catch (Exception error) { callback.complete(null, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }
    private void budget(String key, int limit) {
        long now = android.os.SystemClock.elapsedRealtime();
        ArrayDeque<Long> times = budgets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60000) times.removeFirst();
        if (times.size() >= limit) throw new SecurityException("Plugin operation rate limit");
        times.addLast(now);
    }
    private void rate(String key, long delay) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (requestTimes.containsKey(key) && now - requestTimes.get(key) < delay) throw new SecurityException("Plugin operation rate limit");
        requestTimes.put(key, now);
    }
    @Override public void fault(String session, String error) {
        ArrayList<String> affected = new ArrayList<>();
        for (Map.Entry<String, String> item : sessions.entrySet()) if (session.equals("*") || item.getValue().equals(session)) {
            if (sessions.remove(item.getKey(), item.getValue())) affected.add(item.getKey());
        }
        for (String id : affected) try { worker.execute(() -> { try { disable(id, error); } catch (Exception ignored) { } }); }
        catch (RejectedExecutionException ignored) { }
    }
    private String installedDigest(String id) throws Exception {
        for (String existing : ids()) if (existing.equals(id)) return store().record(id).getString("digest");
        return "";
    }
    private void expireReviews() {
        long now = android.os.SystemClock.elapsedRealtime();
        Iterator<InstallReview> iterator = reviews.values().iterator();
        while (iterator.hasNext()) { InstallReview value = iterator.next(); if (value.expires < now) { value.file.delete(); iterator.remove(); } }
    }
    private InstallReview review(String token) throws Exception {
        expireReviews(); InstallReview result = reviews.get(token);
        if (result == null) throw new ReviewException("review_expired", "Installation review expired; select the package again");
        return result;
    }
    private JSONObject prepareReview(File file) throws Exception {
        expireReviews();
        JSONObject manifest = PluginStore.inspect(file); String id = manifest.getString("id"); String previous = installedDigest(id);
        if (!previous.isEmpty() && !manifest.getString("author").equals(store().info(id).getString("author"))) throw new SecurityException("Plugin author changed");
        while (reviews.size() >= 4) { String oldest = reviews.keySet().iterator().next(); reviews.remove(oldest).file.delete(); }
        InstallReview result = new InstallReview(file, manifest, PluginStore.digest(file), previous); reviews.put(result.token, result);
        return reviewInfo(result);
    }
    private JSONObject reviewInfo(InstallReview value) throws Exception {
        JSONObject info = PluginStore.copy(value.manifest).put("token", value.token).put("digest", value.digest).put("update", !value.previous.isEmpty());
        JSONArray scopes = value.previous.isEmpty() ? new JSONArray() : store().record(value.manifest.getString("id")).optJSONArray("scopes");
        info.put("scopes", scopes == null ? new JSONArray() : new JSONArray(scopes.toString()));
        return new JSONObject().put("review", info).put("error", "");
    }
    private JSONObject confirmReview(JSONObject consent) throws Exception {
        InstallReview value = review(consent.getString("token"));
        if (!value.digest.equals(consent.getString("digest")) || !value.digest.equals(PluginStore.digest(value.file))) {
            reviews.remove(value.token); value.file.delete(); throw new ReviewException("package_changed", "Package changed; select it again");
        }
        String id = value.manifest.getString("id");
        if (!value.previous.equals(installedDigest(id))) throw new ReviewException("install_changed", "Installed version changed; review the package again");
        JSONObject approved = new JSONObject().put("manifest", value.manifest).put("digest", value.digest);
        PluginAccessPolicy.grant(approved, value.manifest.getJSONArray("permissions"), consent.getJSONArray("scopes"));
        reviews.remove(value.token);
        try {
            revoke(id); store().install(value.file);
            JSONObject record = store().record(id);
            PluginAccessPolicy.grant(record, approved.getJSONArray("permissions"), approved.getJSONArray("scopes")); store().save();
            enable(id); publish();
            return new JSONObject().put("plugins", snapshot()).put("installed_id", id).put("error", "");
        } finally { value.file.delete(); }
    }
    public void reviewPackage(Uri uri, Callback callback) { installSource(uri, true, callback); }
    public void install(Uri uri, Callback callback) { installSource(uri, false, callback); }
    private void installSource(Uri uri, boolean review, Callback callback) {
        try {
            worker.execute(() -> {
                File file = null; JSONObject result;
                try {
                    String name = uri.getLastPathSegment();
                    try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0); } catch (UnsupportedOperationException ignored) { }
                    if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".plug")) throw new IllegalArgumentException("Select a .plug package");
                    file = File.createTempFile(review ? "calamus-review-" : "calamus-isolated-", ".plug", context.getCacheDir());
                    try (InputStream source = context.getContentResolver().openInputStream(uri); OutputStream target = new FileOutputStream(file)) { if (source == null) throw new IOException("Cannot open package"); PluginStore.transfer(source, target, 2 * 1024 * 1024); }
                    if (review) { result = prepareReview(file); file = null; } else result = operation("install", file.getAbsolutePath());
                } catch (Exception error) { result = failure(error.getMessage()); }
                finally { if (file != null) file.delete(); }
                JSONObject delivered = result; main.post(() -> callback.complete(delivered));
            });
        } catch (RejectedExecutionException error) { main.post(() -> callback.complete(failure("Plugin queue is full"))); }
    }
    private static JSONObject failure(String text) { try { return new JSONObject().put("error", text); } catch (Exception error) { return new JSONObject(); } }
}
