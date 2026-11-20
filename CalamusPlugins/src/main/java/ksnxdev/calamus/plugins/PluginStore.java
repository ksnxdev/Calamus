package ksnxdev.calamus.plugins;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

final class PluginStore {
    static final Set<String> PERMISSIONS = Set.of("storage", "notifications", "accounts.read", "chats.read", "messages.read", "messages.send", "ui", "commands", "messages.archive", "messages.menu", "presence.control", "presence.read");
    static final Set<String> EVENTS = Set.of("app.started", "app.foreground", "app.background", "account.changed", "chat.opened", "message.received", "message.sent", "message.failed", "message.sending", "message.updated", "settings.changed", "message.deleted", "message.action");
    final File root;
    private final Map<String, JSONObject> records = new LinkedHashMap<>();
    private final Map<String, JSONObject> data = new HashMap<>();
    private final Map<String, JSONObject> settings = new HashMap<>();

    PluginStore(Context context) throws Exception {
        for (File base : List.of(context.getNoBackupFilesDir(), context.getFilesDir(), context.getCacheDir())) {
            try { File canonical = base.getCanonicalFile(); removeLegacyTestData(new File(canonical, "sandbox_runtime"), canonical.getPath() + File.separator); } catch (IOException ignored) { }
        }
        root = new File(context.getNoBackupFilesDir(), "isolated_plugins");
        new File(root, "packages").mkdirs();
        new File(root, "data").mkdirs();
        new File(root, "settings").mkdirs();
        File state = new File(root, "records.json");
        JSONObject saved = read(state, 16 * 1024 * 1024);
        Iterator<String> keys = saved.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            try {
                checkedId(id);
                JSONObject record = saved.getJSONObject(id);
                JSONObject info = inspect(packageFile(id));
                if (!digest(packageFile(id)).equals(record.optString("digest"))) throw new IOException("Package changed");
                boolean resume = record.optBoolean("enabled") && record.optString("digest").equals(record.optString("grant_digest"));
                record.put("manifest", info).put("enabled", false).put("resume", resume);
                records.put(id, record);
            } catch (Exception ignored) { }
        }
        if (!new File(root, "legacy-imported").exists()) {
            File legacy = new File(context.getNoBackupFilesDir(), "python_plugins/installed");
            File[] directories = legacy.listFiles();
            if (directories != null) for (File directory : directories) {
                try {
                    checkedId(directory.getName());
                    File archive = new File(root, "migration.plug");
                    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) { migrate(directory, directory, zip, new int[]{0}); }
                    try { install(archive); } finally { archive.delete(); }
                } catch (Exception ignored) { }
            }
            new File(root, "legacy-imported").createNewFile();
        }
        save();
    }

    private static void removeLegacyTestData(File file, String boundary) throws IOException {
        if (!file.getCanonicalPath().equals(file.getAbsolutePath())) { file.delete(); return; }
        if (!file.getCanonicalPath().startsWith(boundary)) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) removeLegacyTestData(child, file.getCanonicalPath() + File.separator);
        }
        file.delete();
    }

    private static void migrate(File base, File directory, ZipOutputStream zip, int[] count) throws Exception {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.getName().equals("__pycache__") || child.getName().endsWith(".pyc")) continue;
            if (!child.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) throw new IOException("Invalid legacy path");
            if (child.isDirectory()) migrate(base, child, zip, count);
            else {
                if (++count[0] > 128 || child.length() > 8 * 1024 * 1024) throw new IOException("Legacy package is too large");
                zip.putNextEntry(new ZipEntry(child.getAbsolutePath().substring(base.getAbsolutePath().length() + 1)));
                try (InputStream source = new FileInputStream(child)) { transfer(source, zip, 8 * 1024 * 1024); }
                zip.closeEntry();
            }
        }
    }

    static String checkedId(String id) {
        if (id == null || id.length() > 96 || !id.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) throw new IllegalArgumentException("Invalid plugin id");
        return id;
    }

    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static byte[] bytes(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); transfer(stream, output, limit); return output.toByteArray();
    }
    static void transfer(InputStream source, OutputStream target, int limit) throws Exception {
        byte[] buffer = new byte[16384]; int size; int total = 0;
        while ((size = source.read(buffer)) != -1) { total += size; if (total > limit) throw new IOException("Size limit exceeded"); target.write(buffer, 0, size); }
    }
    static JSONObject read(File file) throws Exception {
        return read(file, 1024 * 1024);
    }
    private static JSONObject read(File file, int limit) throws Exception {
        if (!file.exists()) return new JSONObject();
        try (InputStream stream = new AtomicFile(file).openRead()) { return new JSONObject(new String(bytes(stream, limit), StandardCharsets.UTF_8)); }
    }
    static void write(File file, JSONObject value) throws Exception {
        file.getParentFile().mkdirs(); AtomicFile atomic = new AtomicFile(file); FileOutputStream output = atomic.startWrite();
        try { output.write(value.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(output); }
        catch (Exception error) { atomic.failWrite(output); throw error; }
    }
    static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) { byte[] buffer = new byte[16384]; int size; while ((size = stream.read(buffer)) != -1) digest.update(buffer, 0, size); }
        StringBuilder text = new StringBuilder(); for (byte b : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255)); return text.toString();
    }
    static JSONObject inspect(File archive) throws Exception {
        if (archive.length() > 2 * 1024 * 1024) throw new IOException("Plugin archive exceeds 2 MiB");
        Set<String> names = new HashSet<>(); JSONObject manifest = null; int expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry; int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++count > 128 || name.length() > 180 || name.startsWith("/") || name.contains("\\") || name.chars().anyMatch(c -> c < 32) || !names.add(name)) throw new IOException("Invalid archive path");
                String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Invalid archive path");
                if (entry.isDirectory()) continue;
                if (!name.matches(".*\\.(py|json|txt|md|png|jpg|webp)")) throw new IOException("Unsupported plugin file type");
                byte[] content = bytes(zip, name.equals("manifest.json") ? 8192 : 8 * 1024 * 1024 - expanded);
                expanded += content.length;
                if (expanded > 8 * 1024 * 1024) throw new IOException("Expanded plugin exceeds 8 MiB");
                if (name.equals("manifest.json")) manifest = new JSONObject(new String(content, StandardCharsets.UTF_8));
            }
        }
        if (manifest == null || !names.contains("main.py")) throw new IOException("manifest.json and main.py must be at package root");
        checkedId(manifest.getString("id"));
        for (String key : List.of("name", "version", "description", "author")) {
            String value = manifest.getString(key); int limit = key.equals("description") ? 300 : key.equals("version") ? 32 : 80;
            if (value.trim().isEmpty() || value.length() > limit || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0x202a && c <= 0x202e || c >= 0x2066 && c <= 0x2069)) throw new IOException("Invalid manifest field: " + key);
        }
        Object version = manifest.get("api_version");
        if (!(version instanceof Integer) || ((Integer) version < 1 || (Integer) version > 4)) throw new IOException("Unsupported plugin API");
        for (String key : List.of("permissions", "events")) {
            JSONArray list = manifest.optJSONArray(key); if (list == null) list = new JSONArray();
            Set<String> used = new HashSet<>(); Set<String> allowed = key.equals("permissions") ? PERMISSIONS : EVENTS;
            for (int i = 0; i < list.length(); i++) { String value = list.getString(i); if (!allowed.contains(value) || !used.add(value)) throw new IOException("Unsupported or duplicate " + key); }
            manifest.put(key, list);
        }
        JSONArray required = manifest.optJSONArray("required_permissions"); if (required == null) required = new JSONArray();
        Set<String> requested = new HashSet<>(); JSONArray declared = manifest.getJSONArray("permissions");
        for (int i = 0; i < declared.length(); i++) requested.add(declared.getString(i));
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < required.length(); i++) if (!requested.contains(required.getString(i)) || !unique.add(required.getString(i))) throw new IOException("Invalid required permission");
        manifest.put("required_permissions", required);
        JSONArray schema = manifest.optJSONArray("settings"); if (schema == null) schema = new JSONArray();
        if (schema.length() > 24) throw new IOException("Too many plugin settings");
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < schema.length(); i++) {
            JSONObject field = schema.getJSONObject(i); String key = field.getString("key"); String title = field.getString("title");
            if (!key.matches("[a-z][a-z0-9_]{0,47}") || !keys.add(key) || title.isEmpty() || title.length() > 80) throw new IOException("Invalid plugin setting");
            validateSetting(field, field.get("default"));
        }
        manifest.put("settings", schema); return manifest;
    }
    static void validateSetting(JSONObject field, Object value) throws Exception {
        switch (field.getString("type")) {
            case "boolean": if (value instanceof Boolean) return; break;
            case "string": if (value instanceof String && ((String) value).length() <= 1000) return; break;
            case "integer": if (value instanceof Integer && (Integer) value >= -1000000 && (Integer) value <= 1000000) return; break;
        }
        throw new IllegalArgumentException("Invalid setting value");
    }
    File packageFile(String id) { return new File(new File(new File(root, "packages"), checkedId(id)), "package.plug"); }
    synchronized JSONObject record(String id) throws Exception {
        JSONObject result = records.get(checkedId(id)); if (result == null) throw new IllegalArgumentException("Plugin not installed"); return result;
    }
    synchronized JSONObject info(String id) throws Exception { return copy(record(id).getJSONObject("manifest")); }
    synchronized JSONObject install(File archive) throws Exception {
        JSONObject info = inspect(archive); String id = info.getString("id");
        if (!records.containsKey(id) && records.size() >= 32) throw new IOException("Too many installed plugins");
        if (records.containsKey(id) && !info.getString("author").equals(info(id).getString("author"))) throw new IOException("Plugin author changed");
        File packageFile = packageFile(id); packageFile.getParentFile().mkdirs(); AtomicFile atomic = new AtomicFile(packageFile);
        FileOutputStream output = atomic.startWrite();
        try (InputStream source = new FileInputStream(archive)) { transfer(source, output, 2 * 1024 * 1024); atomic.finishWrite(output); }
        catch (Exception error) { atomic.failWrite(output); throw error; }
        JSONObject record = new JSONObject().put("manifest", info).put("digest", digest(packageFile)).put("enabled", false).put("permissions", new JSONArray()).put("scopes", new JSONArray()).put("error", "");
        records.put(id, record); save(); return copy(record);
    }
    synchronized void save() throws Exception {
        JSONObject value = new JSONObject(); for (Map.Entry<String, JSONObject> entry : records.entrySet()) value.put(entry.getKey(), entry.getValue()); write(new File(root, "records.json"), value);
    }
    synchronized JSONObject values(String id, boolean parameters) throws Exception {
        Map<String, JSONObject> cache = parameters ? settings : data;
        if (!cache.containsKey(id)) { JSONObject stored; try { stored = read(new File(new File(root, parameters ? "settings" : "data"), checkedId(id) + ".json")); } catch (Exception error) { stored = new JSONObject(); } cache.put(id, stored); }
        return cache.get(id);
    }
    synchronized Object get(String id, String key, Object fallback) throws Exception { return values(id, false).opt(key) == null ? fallback : values(id, false).get(key); }
    synchronized void set(String id, String key, Object value) throws Exception {
        if (key.isEmpty() || key.length() > 128) throw new IOException("Invalid storage key");
        JSONObject next = copy(values(id, false)); next.put(key, value);
        if (next.toString().getBytes(StandardCharsets.UTF_8).length > 32768) throw new IOException("Plugin storage exceeds 32 KiB");
        write(new File(new File(root, "data"), checkedId(id) + ".json"), next); data.put(id, next);
    }
    synchronized Object setting(String id, String key) throws Exception {
        JSONArray schema = info(id).getJSONArray("settings");
        for (int i = 0; i < schema.length(); i++) {
            JSONObject field = schema.getJSONObject(i); if (!field.getString("key").equals(key)) continue;
            Object value = values(id, true).opt(key); if (value == null) return field.get("default");
            try { validateSetting(field, value); return value; } catch (Exception error) { return field.get("default"); }
        }
        throw new IOException("Unknown plugin setting");
    }
    synchronized void setting(String id, String key, Object value) throws Exception {
        JSONArray schema = info(id).getJSONArray("settings");
        for (int i = 0; i < schema.length(); i++) {
            JSONObject field = schema.getJSONObject(i); if (!field.getString("key").equals(key)) continue;
            validateSetting(field, value); JSONObject next = copy(values(id, true)); next.put(key, value);
            write(new File(new File(root, "settings"), id + ".json"), next); settings.put(id, next); return;
        }
        throw new IOException("Unknown plugin setting");
    }
    synchronized JSONArray snapshot() throws Exception {
        JSONArray list = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : records.entrySet()) {
            String id = entry.getKey(); JSONObject record = entry.getValue(); JSONObject value = copy(record.getJSONObject("manifest"));
            JSONObject fields = new JSONObject(); JSONArray schema = value.getJSONArray("settings");
            for (int i = 0; i < schema.length(); i++) { String key = schema.getJSONObject(i).getString("key"); fields.put(key, setting(id, key)); }
            value.put("enabled", record.optBoolean("enabled")).put("error", record.optString("error")).put("logs", record.optJSONArray("logs") == null ? new JSONArray() : record.optJSONArray("logs"))
                    .put("commands", record.optJSONObject("commands") == null ? new JSONObject() : record.optJSONObject("commands")).put("setting_values", fields)
                    .put("digest", record.getString("digest")).put("access_granted", record.getString("digest").equals(record.optString("grant_digest"))).put("granted_permissions", record.optJSONArray("permissions")).put("scopes", record.optJSONArray("scopes")); list.put(value);
        }
        return list;
    }
    synchronized void remove(String id) throws Exception {
        record(id); records.remove(id); data.remove(id); settings.remove(id); save();
        new AtomicFile(packageFile(id)).delete(); packageFile(id).getParentFile().delete();
        new AtomicFile(new File(new File(root, "data"), id + ".json")).delete();
        new AtomicFile(new File(new File(root, "settings"), id + ".json")).delete();
    }
}
