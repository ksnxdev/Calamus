package ksnxdev.calamus.plugins.ui;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import java.util.ArrayList;
import ksnxdev.calamus.plugins.PluginManager;
import uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry;
import uz.unnarsx.cherrygram.preferences.helpers.SettingsHelper;

public class PluginSettingsActivity extends BaseCGPreferencesEntry {
    private final String pluginId;
    private JSONObject plugin = new JSONObject();
    private String error = "";

    public PluginSettingsActivity(String pluginId) { this.pluginId = pluginId; }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.CalamusPluginSettings);
    }

    @Override
    public void onResume() {
        super.onResume();
        execute("list", "", this::received);
    }

    private PluginManager manager() {
        return PluginManager.getInstance(ApplicationLoader.applicationContext);
    }

    private void execute(String operation, String argument, PluginManager.Callback callback) {
        manager().execute(operation, argument, callback);
    }

    private void received(JSONObject result) {
        if (isFinished) return;
        error = result.optString("error");
        JSONArray plugins = result.optJSONArray("plugins");
        if (plugins != null) {
            for (int i = 0; i < plugins.length(); i++) {
                if (plugins.optJSONObject(i).optString("id").equals(pluginId)) plugin = plugins.optJSONObject(i);
            }
        }
        if (listView != null) listView.adapter.update(true);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(plugin.optString("name", pluginId)));
        JSONArray schema = plugin.optJSONArray("settings");
        JSONObject values = plugin.optJSONObject("setting_values");
        if (schema != null && values != null) {
            for (int i = 0; i < schema.length(); i++) {
                JSONObject setting = schema.optJSONObject(i);
                String key = setting.optString("key");
                if (setting.optString("type").equals("boolean")) {
                    boolean ghost = PluginGhostSettings.supports(plugin, key);
                    UItem control = SettingsHelper.asSwitchCG(100 + i, setting.optString("title")).setChecked(ghost ? PluginGhostSettings.checked(plugin) : values.optBoolean(key));
                    control.enabled = !ghost || PluginGhostSettings.available(plugin); items.add(control);
                } else {
                    items.add(UItem.asButton(100 + i, setting.optString("title"), String.valueOf(values.opt(key))));
                }
            }
        }
        items.add(UItem.asShadow(error.isEmpty() ? null : error));
    }

    private void save(String key, Object value) {
        try {
            execute("setting", new JSONObject().put("plugin_id", pluginId).put("key", key).put("value", value).toString(), this::received);
        } catch (Exception failure) {
            error = failure.getMessage();
            listView.adapter.update(true);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        JSONArray schema = plugin.optJSONArray("settings");
        if (schema == null || item.id < 100 || item.id - 100 >= schema.length()) return;
        JSONObject setting = schema.optJSONObject(item.id - 100);
        JSONObject values = plugin.optJSONObject("setting_values");
        String key = setting.optString("key");
        if (setting.optString("type").equals("boolean")) {
            boolean ghost = PluginGhostSettings.supports(plugin, key);
            if (ghost && !PluginGhostSettings.available(plugin)) return;
            save(key, ghost ? !PluginGhostSettings.checked(plugin) : !values.optBoolean(key));
            return;
        }
        EditText input = new EditText(getParentActivity());
        input.setText(String.valueOf(values.opt(key)));
        input.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
        input.setInputType(setting.optString("type").equals("integer") ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED : InputType.TYPE_CLASS_TEXT);
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        dialog.setTitle(setting.optString("title"));
        dialog.setView(input);
        dialog.setPositiveButton(LocaleController.getString(R.string.Save), (window, button) -> {
            try {
                Object value = setting.optString("type").equals("integer") ? Integer.parseInt(input.getText().toString()) : input.getText().toString();
                save(key, value);
            } catch (NumberFormatException failure) {
                error = LocaleController.getString(R.string.CalamusPluginInvalidNumber);
                listView.adapter.update(true);
            }
        });
        dialog.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(dialog.create());
    }
}
