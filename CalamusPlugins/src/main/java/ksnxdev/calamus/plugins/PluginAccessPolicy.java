package ksnxdev.calamus.plugins;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

public final class PluginAccessPolicy {
    public static final int MAX_CHAT_SCOPES = 4096;
    public static boolean has(JSONObject record, String permission) {
        JSONArray values = record.optJSONArray("permissions");
        if (values != null) for (int i = 0; i < values.length(); i++) if (permission.equals(values.optString(i))) return true;
        return false;
    }
    public static boolean scope(JSONObject record, JSONObject args, boolean dialog) {
        JSONArray scopes = record.optJSONArray("scopes");
        if (scopes == null || !args.has("account_id") || !args.has("user_id") || dialog && args.optLong("dialog_id") == 0) return false;
        for (int i = 0; i < scopes.length(); i++) {
            JSONObject value = scopes.optJSONObject(i); if (value == null) continue;
            if (value.optInt("account_id", -1) == args.optInt("account_id", -2) && value.optLong("user_id") == args.optLong("user_id", -1)
                    && (!dialog || value.optLong("dialog_id") == args.optLong("dialog_id", 0))) return true;
        }
        return false;
    }
    public static boolean accountScope(JSONObject record, JSONObject args) {
        JSONArray scopes = record.optJSONArray("scopes");
        if (scopes == null || !args.has("account_id") || !args.has("user_id")) return false;
        for (int i = 0; i < scopes.length(); i++) {
            JSONObject scope = scopes.optJSONObject(i);
            if (scope != null && scope.optLong("dialog_id") == 0 && scope.optInt("account_id", -1) == args.optInt("account_id", -2) && scope.optLong("user_id") == args.optLong("user_id", -1)) return true;
        }
        return false;
    }
    public static String permission(String operation) {
        switch (operation) {
            case "archive.record": case "archive.query": return "messages.archive";
            case "archive.show": return "ui";
            case "message.action.register": return "messages.menu";
            case "presence.set": return "presence.control";
            case "presence.get": return "presence.read";
            case "accounts": case "validate_account": return "accounts.read";
            case "chats": return "chats.read";
            case "history": return "messages.read";
            case "send_message": return "messages.send";
            case "show_dialog": case "open_chat": return "ui";
            case "store.get": case "store.set": return "storage";
            case "command.register": return "commands";
            case "notify": return "notifications";
            case "setting.get": case "log": return "";
            default: throw new IllegalArgumentException("Operation is not allowed");
        }
    }
    public static void authorize(JSONObject record, String operation, JSONObject arguments) {
        if (!record.optBoolean("enabled")) throw new SecurityException("Plugin is not enabled");
        String permission = permission(operation);
        if (!permission.isEmpty() && !has(record, permission)) throw new SecurityException("Permission was not granted: " + permission);
        if ((operation.startsWith("archive.") || operation.equals("message.action.register")) && !has(record, "messages.read")) throw new SecurityException("Message read access is required");
        JSONObject manifest = record.optJSONObject("manifest");
        if (operation.equals("presence.set") && manifest != null && manifest.optInt("api_version") >= 4 && !accountScope(record, arguments)) throw new SecurityException("Account status access was not granted");
        if (operation.equals("archive.show") && !has(record, "messages.archive")) throw new SecurityException("Archive permission is required");
        if (Set.of("presence.set", "presence.get", "archive.record", "archive.query", "archive.show", "validate_account", "chats", "history", "send_message", "show_dialog", "open_chat").contains(operation)) {
            boolean dialog = Set.of("history", "send_message", "open_chat", "archive.record", "archive.query", "archive.show", "presence.get").contains(operation);
            if (!scope(record, arguments, dialog)) throw new SecurityException("Account or chat access was not granted");
        }
    }
    public static void grant(JSONObject record, JSONArray permissions, JSONArray scopes) throws Exception {
        JSONArray requested = record.getJSONObject("manifest").getJSONArray("permissions");
        java.util.Set<String> declared = new java.util.HashSet<>(); for (int i = 0; i < requested.length(); i++) declared.add(requested.getString(i));
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < permissions.length(); i++) if (!declared.contains(permissions.getString(i)) || !used.add(permissions.getString(i))) throw new SecurityException("Invalid permission grant");
        if (scopes.length() > MAX_CHAT_SCOPES) throw new SecurityException("Too many chat grants");
        JSONArray clean = new JSONArray();
        for (int i = 0; i < scopes.length(); i++) {
            JSONObject scope = scopes.getJSONObject(i);
            int account = scope.getInt("account_id"); long user = scope.getLong("user_id"); long dialog = scope.getLong("dialog_id");
            if (account < 0 || account >= 32 || user <= 0) throw new SecurityException("Invalid account/chat grant");
            clean.put(new JSONObject().put("account_id", account).put("user_id", user).put("dialog_id", dialog));
        }
        record.put("permissions", new JSONArray(permissions.toString())).put("scopes", clean).put("grant_digest", record.getString("digest"));
    }
}
