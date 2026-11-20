package ksnxdev.calamus.plugins.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import java.util.LinkedHashSet;
import java.util.Set;
import ksnxdev.calamus.plugins.PluginPresenceController;

final class PluginGhostSettings {
    private PluginGhostSettings() {}
    private static boolean permission(JSONObject plugin, String field) {
        JSONArray values = plugin.optJSONArray(field);
        if (values != null) for (int i = 0; i < values.length(); i++) if ("presence.control".equals(values.optString(i))) return true;
        return false;
    }
    static boolean supports(JSONObject plugin, String key) {
        if (!"ghost".equals(key) || !permission(plugin, "permissions")) return false;
        JSONArray schema = plugin.optJSONArray("settings");
        if (schema != null) for (int i = 0; i < schema.length(); i++) {
            JSONObject setting = schema.optJSONObject(i);
            if (setting != null && key.equals(setting.optString("key")) && "boolean".equals(setting.optString("type"))) return true;
        }
        return false;
    }
    private static Set<Integer> accounts(JSONObject plugin) {
        Set<Integer> accounts = new LinkedHashSet<>(); JSONArray scopes = plugin.optJSONArray("scopes");
        if (scopes != null) for (int i = 0; i < scopes.length(); i++) {
            JSONObject scope = scopes.optJSONObject(i); if (scope == null || plugin.optInt("api_version") >= 4 && scope.optLong("dialog_id") != 0) continue;
            int account = scope.optInt("account_id", -1);
            if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(account).isClientActivated() && UserConfig.getInstance(account).getClientUserId() == scope.optLong("user_id")) accounts.add(account);
        }
        return accounts;
    }
    static boolean available(JSONObject plugin) {
        return plugin.optBoolean("enabled") && permission(plugin, "granted_permissions") && !accounts(plugin).isEmpty();
    }
    static boolean checked(JSONObject plugin) {
        if (!available(plugin)) return false;
        for (int account : accounts(plugin)) if (!PluginPresenceController.enabledForPlugin(plugin.optString("id"), account)) return false;
        return true;
    }
}
