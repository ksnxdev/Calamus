package ksnxdev.calamus.plugins.ui;

import static org.telegram.messenger.LocaleController.getString;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import ksnxdev.calamus.plugins.PluginManager;
import ksnxdev.calamus.plugins.TelegramPluginHost;
import uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry;
import uz.unnarsx.cherrygram.preferences.helpers.SettingsHelper;

public class PluginAccessActivity extends BaseCGPreferencesEntry {
    private final String id;
    private JSONObject plugin = new JSONObject();
    private JSONArray choices = new JSONArray();
    private final Set<String> permissions = new HashSet<>();
    private final Set<Integer> scopes = new HashSet<>();
    private String error = "";
    private boolean loaded;
    private boolean busy;
    public PluginAccessActivity(String id) { this.id = id; }
    private PluginManager manager() { return PluginManager.getInstance(ApplicationLoader.applicationContext); }
    @Override protected CharSequence getTitle() { return getString(R.string.CalamusPluginAccess); }
    @Override public void onResume() {
        super.onResume();
        if (loaded) return;
        manager().execute("list", "", result -> {
            if (isFinished) return;
            try {
                JSONArray plugins = result.getJSONArray("plugins");
                for (int i = 0; i < plugins.length(); i++) if (plugins.getJSONObject(i).getString("id").equals(id)) plugin = plugins.getJSONObject(i);
                JSONArray granted = plugin.optJSONArray("granted_permissions");
                if (granted != null) for (int i = 0; i < granted.length(); i++) permissions.add(granted.getString(i));
                JSONArray required = plugin.optJSONArray("required_permissions");
                if ((granted == null || granted.length() == 0) && required != null) for (int i = 0; i < required.length(); i++) permissions.add(required.getString(i));
                choices = TelegramPluginHost.accessOptions(plugin.optJSONArray("scopes"));
                JSONArray saved = plugin.optJSONArray("scopes");
                if (saved != null) for (int i = 0; i < choices.length(); i++) for (int j = 0; j < saved.length(); j++) {
                    JSONObject a = choices.getJSONObject(i), b = saved.getJSONObject(j);
                    if (a.getInt("account_id") == b.getInt("account_id") && a.getLong("user_id") == b.getLong("user_id") && a.getLong("dialog_id") == b.getLong("dialog_id")) scopes.add(i);
                }
                loaded = true;
            } catch (Exception failure) { error = result.optString("error", failure.getMessage()); }
            if (listView != null) listView.adapter.update(true);
        });
    }
    public static String permissionTitle(String key) {
        switch (key) {
            case "messages.archive": return getString(R.string.CalamusPermissionArchive);
            case "messages.menu": return getString(R.string.CalamusPermissionMessageMenu);
            case "presence.control": return getString(R.string.CalamusPermissionPresenceControl);
            case "presence.read": return getString(R.string.CalamusPermissionPresenceRead);
            case "storage": return getString(R.string.CalamusPermissionStorage);
            case "notifications": return getString(R.string.CalamusPermissionNotifications);
            case "accounts.read": return getString(R.string.CalamusPermissionAccounts);
            case "chats.read": return getString(R.string.CalamusPermissionChats);
            case "messages.read": return getString(R.string.CalamusPermissionRead);
            case "messages.send": return getString(R.string.CalamusPermissionSend);
            case "ui": return getString(R.string.CalamusPermissionUI);
            case "commands": return getString(R.string.CalamusPermissionCommands);
            default: return key;
        }
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(plugin.optString("name", id)));
        items.add(UItem.asButton(1, getString(R.string.CalamusPluginSaveEnable)));
        items.add(UItem.asShadow(getString(R.string.CalamusPluginAccessDescription)));
        String hash = plugin.optString("digest");
        if (!hash.isEmpty()) items.add(UItem.asShadow("SHA-256: " + hash));
        if (!error.isEmpty()) items.add(UItem.asShadow(error));
        items.add(UItem.asHeader(getString(R.string.CalamusPluginCapabilities)));
        JSONArray requested = plugin.optJSONArray("permissions");
        if (requested != null) for (int i = 0; i < requested.length(); i++) {
            String permission = requested.optString(i);
            String title = permissionTitle(permission);
            if (isRequired(permission)) title += " · " + getString(R.string.CalamusPluginRequired);
            items.add(SettingsHelper.asSwitchCG(100 + i, title).setChecked(permissions.contains(permission)));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(getString(R.string.CalamusPluginAllowedChats)));
        if (choices.length() == 0) items.add(UItem.asShadow(getString(R.string.CalamusPluginNoAccounts)));
        if (choices.length() > 0) items.add(UItem.asButton(2, getString(scopes.size() == choices.length() ? R.string.DeselectAll : R.string.SelectAll)));
        for (int i = 0; i < choices.length(); i++) items.add(SettingsHelper.asSwitchCG(1000 + i, choices.optJSONObject(i).optString("title")).setChecked(scopes.contains(i)));
        items.add(UItem.asShadow(null));
        if (busy || !loaded) for (UItem item : items) item.enabled = false;
    }
    private boolean isRequired(String key) {
        JSONArray required = plugin.optJSONArray("required_permissions");
        if (required != null) for (int i = 0; i < required.length(); i++) if (key.equals(required.optString(i))) return true;
        return false;
    }
    private String missingRequired() {
        ArrayList<String> missing = new ArrayList<>();
        JSONArray required = plugin.optJSONArray("required_permissions");
        if (required != null) for (int i = 0; i < required.length(); i++) {
            String key = required.optString(i);
            if (!permissions.contains(key)) missing.add(permissionTitle(key));
        }
        return android.text.TextUtils.join(", ", missing);
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (busy || !loaded) return;
        if (item.id == 2) {
            if (scopes.size() == choices.length()) scopes.clear();
            else for (int i = 0; i < choices.length(); i++) scopes.add(i);
            error = "";
        } else if (item.id >= 1000) {
            int index = item.id - 1000;
            if (!scopes.remove(index)) scopes.add(index);
        } else if (item.id >= 100) {
            String key = plugin.optJSONArray("permissions").optString(item.id - 100);
            if (!permissions.remove(key)) permissions.add(key);
        } else if (item.id == 1) {
            String missing = missingRequired();
            if (!missing.isEmpty()) { error = getString(R.string.CalamusPluginMissingRequired) + " " + missing; listView.adapter.update(true); return; }
            try {
                JSONArray selected = new JSONArray(); for (int index : scopes) selected.put(choices.getJSONObject(index));
                JSONObject value = new JSONObject().put("plugin_id", id).put("permissions", new JSONArray(permissions)).put("scopes", selected);
                busy = true;
                manager().execute("grant", value.toString(), result -> {
                    if (isFinished) return;
                    if (!result.optString("error").isEmpty()) { busy = false; error = result.optString("error"); listView.adapter.update(true); return; }
                    manager().execute("enable", id, enabled -> {
                        if (isFinished) return;
                        busy = false;
                        error = enabled.optString("error");
                        JSONArray plugins = enabled.optJSONArray("plugins");
                        if (plugins != null) for (int i = 0; i < plugins.length(); i++) if (plugins.optJSONObject(i).optString("id").equals(id)) error = plugins.optJSONObject(i).optString("error", error);
                        if (error.isEmpty()) finishFragment(); else listView.adapter.update(true);
                    });
                });
            } catch (Exception failure) { error = failure.getMessage(); }
        }
        listView.adapter.update(item.id != 2);
    }
}
