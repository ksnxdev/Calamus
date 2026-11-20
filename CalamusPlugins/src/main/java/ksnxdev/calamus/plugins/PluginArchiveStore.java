package ksnxdev.calamus.plugins;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

public final class PluginArchiveStore {
    private PluginArchiveStore() {}
    private static File file(Context context, String plugin) {
        File directory = new File(context.getNoBackupFilesDir(), "isolated_plugins/archives");
        directory.mkdirs();
        return new File(directory, PluginStore.checkedId(plugin) + ".db");
    }
    private static SQLiteDatabase open(Context context, String plugin) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file(context, plugin), null);
        DatabaseUtils.longForQuery(db, "PRAGMA busy_timeout=3000", null);
        DatabaseUtils.longForQuery(db, "PRAGMA max_page_count=" + (16 * 1024 * 1024 / DatabaseUtils.longForQuery(db, "PRAGMA page_size", null)), null);
        DatabaseUtils.longForQuery(db, "PRAGMA journal_size_limit=65536", null);
        db.execSQL("CREATE TABLE IF NOT EXISTS journal (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL, channel_id INTEGER NOT NULL, date INTEGER NOT NULL, edit_date INTEGER NOT NULL, captured INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL, media TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS journal_message ON journal(user_id,dialog_id,message_id,id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS journal_delete ON journal(user_id,channel_id,message_id)");
        if (db.getVersion() < 1) {
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE journal ADD COLUMN sender_id INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE journal ADD COLUMN outgoing INTEGER NOT NULL DEFAULT 0");
                db.setVersion(1); db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS retained_chats (user_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, last_id INTEGER NOT NULL, PRIMARY KEY(user_id,dialog_id))");
        return db;
    }
    private static String searchable(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
    }
    private static JSONObject record(SQLiteDatabase db, long user, long dialog, JSONObject message) throws Exception {
        int id = message.getInt("id");
        if (id <= 0 || message.getLong("dialog_id") != dialog || !message.optBoolean("archivable")) return new JSONObject().put("stored", false);
        String text = message.optString("text"), media = message.optString("media_type");
        if (text.codePointCount(0, text.length()) > 4096 || media.length() > 40) throw new IllegalArgumentException("Archive message is too large");
        String[] key = {Long.toString(user), Long.toString(dialog), Integer.toString(id)};
        boolean deleted = false;
        try (Cursor previous = db.rawQuery("SELECT text,media,edit_date,deleted FROM journal WHERE user_id=? AND dialog_id=? AND message_id=? ORDER BY id DESC LIMIT 1", key)) {
            if (previous.moveToFirst()) {
                deleted = previous.getInt(3) != 0;
                if (previous.getInt(2) > message.optInt("edit_date")) return new JSONObject().put("stored", false);
                if (previous.getString(0).equals(text) && previous.getString(1).equals(media)) return new JSONObject().put("stored", false);
            }
        }
        long pages = DatabaseUtils.longForQuery(db, "PRAGMA page_count", null) - DatabaseUtils.longForQuery(db, "PRAGMA freelist_count", null);
        if (pages * DatabaseUtils.longForQuery(db, "PRAGMA page_size", null) >= 12 * 1024 * 1024) {
            long prune = Math.max(1, Math.min(2000, DatabaseUtils.queryNumEntries(db, "journal") / 4));
            db.execSQL("DELETE FROM journal WHERE id IN (SELECT id FROM journal ORDER BY id LIMIT " + prune + ")");
        }
        ContentValues values = new ContentValues();
        values.put("user_id", user); values.put("dialog_id", dialog); values.put("message_id", id);
        values.put("channel_id", message.optLong("channel_id")); values.put("date", message.optInt("date"));
        values.put("edit_date", message.optInt("edit_date")); values.put("captured", System.currentTimeMillis());
        values.put("deleted", deleted ? 1 : 0);
        values.put("text", text); values.put("media", media);
        values.put("sender_id", message.optLong("sender_id")); values.put("outgoing", message.optBoolean("outgoing") ? 1 : 0);
        long version = db.insertOrThrow("journal", null, values);
        db.execSQL("DELETE FROM journal WHERE id IN (SELECT id FROM journal ORDER BY id DESC LIMIT -1 OFFSET 20000)");
        return new JSONObject().put("stored", true).put("version_id", version);
    }
    static synchronized int recordBatch(Context context, String plugin, JSONObject scope, JSONArray messages, java.util.function.BooleanSupplier authorized) throws Exception {
        if (messages.length() > 1000) throw new IllegalArgumentException("Archive batch exceeds 1000 messages");
        int stored = 0;
        try (SQLiteDatabase db = open(context, plugin)) {
            db.beginTransaction();
            try {
                for (int i = 0; i < messages.length(); i++) {
                    if (!authorized.getAsBoolean()) throw new SecurityException("Plugin archive access was revoked");
                    if (record(db, scope.getLong("user_id"), scope.getLong("dialog_id"), messages.getJSONObject(i)).optBoolean("stored")) stored++;
                }
                if (!authorized.getAsBoolean()) throw new SecurityException("Plugin archive access was revoked");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        return stored;
    }
    public static synchronized JSONObject request(Context context, String plugin, String operation, JSONObject args) throws Exception {
        long user = args.getLong("user_id"), dialog = args.getLong("dialog_id");
        try (SQLiteDatabase db = open(context, plugin)) {
            if (operation.equals("archive.record")) {
                return record(db, user, dialog, args.getJSONObject("message"));
            }
            if (!operation.equals("archive.query")) throw new IllegalArgumentException("Unknown archive operation");
            long version = args.optLong("version_id"); int message = args.optInt("message_id");
            int limit = Math.max(1, Math.min(30, args.optInt("limit", 20))), offset = Math.max(0, Math.min(20000, args.optInt("offset")));
            String where = "user_id=? AND dialog_id=?";
            java.util.ArrayList<String> params = new java.util.ArrayList<>(); params.add(Long.toString(user)); params.add(Long.toString(dialog));
            if (version > 0) { where += " AND id=?"; params.add(Long.toString(version)); }
            else if (message > 0) { where += " AND message_id=?"; params.add(Integer.toString(message)); }
            else where += " AND id IN (SELECT MAX(id) FROM journal WHERE user_id=? AND dialog_id=? GROUP BY message_id)";
            if (version <= 0 && message <= 0) { params.add(Long.toString(user)); params.add(Long.toString(dialog)); }
            if (args.optBoolean("deleted_only")) where += " AND deleted=1";
            String search = args.optString("search").trim();
            if (search.length() > 200) throw new IllegalArgumentException("Search is too long");
            String needle = searchable(search);
            JSONArray rows = new JSONArray();
            try (Cursor cursor = db.query("journal", null, where, params.toArray(new String[0]), null, null, "id DESC", search.isEmpty() ? offset + "," + limit : null)) {
                int matched = 0;
                while (cursor.moveToNext()) {
                    if (!needle.isEmpty()) {
                        if (!searchable(cursor.getString(cursor.getColumnIndexOrThrow("text"))).contains(needle)) continue;
                        if (matched++ < offset) continue;
                        if (rows.length() == limit) break;
                    }
                    JSONObject row = new JSONObject();
                    for (String name : new String[]{"id","message_id","date","edit_date","captured","deleted"}) row.put(name, cursor.getLong(cursor.getColumnIndexOrThrow(name)));
                    String text = cursor.getString(cursor.getColumnIndexOrThrow("text"));
                    row.put("text", version > 0 ? text : text.substring(0, text.offsetByCodePoints(0, Math.min(180, text.codePointCount(0, text.length())))));
                    row.put("media_type", cursor.getString(cursor.getColumnIndexOrThrow("media")));
                    rows.put(row);
                }
            }
            return new JSONObject().put("rows", rows).put("offset", offset).put("limit", limit);
        }
    }
    static synchronized JSONArray deletedInRange(Context context, String plugin, long user, long dialog, int minimum, int maximum) throws Exception {
        JSONArray rows = new JSONArray();
        if (!file(context, plugin).exists()) return rows;
        try (SQLiteDatabase db = open(context, plugin); Cursor cursor = db.rawQuery("SELECT * FROM journal WHERE user_id=? AND dialog_id=? AND deleted=1 AND message_id>=? AND message_id<=? AND id IN (SELECT MAX(id) FROM journal WHERE user_id=? AND dialog_id=? GROUP BY message_id) ORDER BY message_id DESC LIMIT 500", new String[]{Long.toString(user),Long.toString(dialog),Integer.toString(minimum),Integer.toString(maximum),Long.toString(user),Long.toString(dialog)})) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (String name : new String[]{"message_id","date","edit_date","sender_id","outgoing"}) row.put(name, cursor.getLong(cursor.getColumnIndexOrThrow(name)));
                row.put("text", cursor.getString(cursor.getColumnIndexOrThrow("text"))).put("media_type", cursor.getString(cursor.getColumnIndexOrThrow("media")));
                rows.put(row);
            }
        }
        return rows;
    }
    static synchronized JSONArray deleted(Context context, String plugin, long user, long channel, JSONArray ids, JSONArray scopes) throws Exception {
        JSONArray affected = new JSONArray();
        if (!file(context, plugin).exists()) return affected;
        try (SQLiteDatabase db = open(context, plugin)) {
            db.beginTransaction();
            try {
                java.util.Map<Long, java.util.ArrayList<JSONObject>> granted = new java.util.LinkedHashMap<>();
                for (int s = 0; s < scopes.length(); s++) {
                    JSONObject scope = scopes.getJSONObject(s); if (scope.optLong("user_id") != user) continue;
                    granted.computeIfAbsent(scope.getLong("dialog_id"), ignored -> new java.util.ArrayList<>()).add(scope);
                }
                java.util.Map<Long, java.util.LinkedHashSet<Integer>> matched = new java.util.LinkedHashMap<>();
                int count = Math.min(1000, ids.length());
                for (int start = 0; start < count && !granted.isEmpty(); start += 256) {
                    int end = Math.min(count, start + 256);
                    java.util.ArrayList<String> params = new java.util.ArrayList<>(); params.add(Long.toString(user)); params.add(Long.toString(channel));
                    for (int i = start; i < end; i++) params.add(Integer.toString(ids.getInt(i)));
                    String placeholders = android.text.TextUtils.join(",", java.util.Collections.nCopies(end - start, "?"));
                    try (Cursor cursor = db.rawQuery("SELECT DISTINCT dialog_id,message_id FROM journal WHERE user_id=? AND channel_id=? AND message_id IN (" + placeholders + ")", params.toArray(new String[0]))) {
                        while (cursor.moveToNext()) {
                            long dialog = cursor.getLong(0);
                            if (granted.containsKey(dialog)) matched.computeIfAbsent(dialog, ignored -> new java.util.LinkedHashSet<>()).add(cursor.getInt(1));
                        }
                    }
                }
                ContentValues values = new ContentValues(); values.put("deleted", 1);
                for (java.util.Map.Entry<Long, java.util.LinkedHashSet<Integer>> entry : matched.entrySet()) {
                    long dialog = entry.getKey(); JSONArray found = new JSONArray();
                    for (int id : entry.getValue()) {
                        db.update("journal", values, "user_id=? AND dialog_id=? AND channel_id=? AND message_id=?", new String[]{Long.toString(user),Long.toString(dialog),Long.toString(channel),Integer.toString(id)});
                        found.put(id);
                    }
                    for (JSONObject scope : granted.get(dialog)) affected.put(new JSONObject(scope.toString()).put("ids", found));
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        return affected;
    }
    static synchronized int latestMessageId(Context context, String plugin, long user, long dialog) {
        if (!file(context, plugin).exists()) return 0;
        try (SQLiteDatabase db = open(context, plugin)) {
            return (int) DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(message_id),0) FROM journal WHERE user_id=? AND dialog_id=?", new String[]{Long.toString(user), Long.toString(dialog)});
        }
    }
    static synchronized boolean retainedState(Context context, String plugin, long user, long dialog, String state, int messageId) {
        if (user <= 0 || dialog <= 0 || user == dialog || !file(context, plugin).exists()) return false;
        try (SQLiteDatabase db = open(context, plugin)) {
            String[] key = {Long.toString(user), Long.toString(dialog)};
            if (state.equals("active")) return db.delete("retained_chats", "user_id=? AND dialog_id=? AND last_id<?", new String[]{key[0], key[1], Integer.toString(messageId)}) > 0;
            long maximum = DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(message_id),0) FROM journal WHERE user_id=? AND dialog_id=?", key);
            if (state.equals("deleted")) {
                if (maximum <= 0 || DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM journal WHERE user_id=? AND dialog_id=? AND deleted=1", key) == 0) return false;
                try (Cursor previous = db.rawQuery("SELECT hidden FROM retained_chats WHERE user_id=? AND dialog_id=?", key)) {
                    if (previous.moveToFirst() && previous.getInt(0) != 0) return false;
                }
            } else if (!state.equals("hidden")) throw new IllegalArgumentException("Unknown retained chat state");
            ContentValues values = new ContentValues(); values.put("user_id", user); values.put("dialog_id", dialog);
            values.put("hidden", state.equals("hidden") ? 1 : 0); values.put("last_id", maximum);
            db.insertWithOnConflict("retained_chats", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            return true;
        }
    }
    static synchronized JSONArray retainedChats(Context context, String plugin, long user) throws Exception {
        JSONArray rows = new JSONArray();
        if (!file(context, plugin).exists()) return rows;
        try (SQLiteDatabase db = open(context, plugin); Cursor cursor = db.rawQuery("SELECT r.dialog_id,r.last_id,j.* FROM retained_chats r JOIN journal j ON j.id=(SELECT id FROM journal WHERE user_id=r.user_id AND dialog_id=r.dialog_id AND deleted=1 ORDER BY message_id DESC,id DESC LIMIT 1) WHERE r.user_id=? AND r.hidden=0 ORDER BY j.date DESC LIMIT 2000", new String[]{Long.toString(user)})) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (String name : new String[]{"dialog_id","last_id","message_id","date","edit_date","sender_id","outgoing"}) row.put(name, cursor.getLong(cursor.getColumnIndexOrThrow(name)));
                row.put("text", cursor.getString(cursor.getColumnIndexOrThrow("text"))).put("media_type", cursor.getString(cursor.getColumnIndexOrThrow("media")));
                rows.put(row);
            }
        }
        return rows;
    }
    static synchronized void clearUser(Context context, String plugin, long user) {
        if (!file(context, plugin).exists()) return;
        try (SQLiteDatabase db = open(context, plugin)) { db.delete("journal", "user_id=?", new String[]{Long.toString(user)}); db.delete("retained_chats", "user_id=?", new String[]{Long.toString(user)}); }
    }
    static synchronized void remove(Context context, String plugin) { SQLiteDatabase.deleteDatabase(file(context, plugin)); }
}
