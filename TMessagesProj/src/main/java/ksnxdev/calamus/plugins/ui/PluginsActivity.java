package ksnxdev.calamus.plugins.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

import ksnxdev.calamus.plugins.PluginManager;
import uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry;

public class PluginsActivity extends BaseCGPreferencesEntry {
    private static final int IMPORT = 1;
    private static final int DISABLE_ALL = 3;
    private static final int PICK_PLUGIN = 2914;
    private JSONArray plugins = new JSONArray();
    private boolean loading = true;
    private String error = "";

    private PluginManager manager() {
        return PluginManager.getInstance(ApplicationLoader.applicationContext);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.CalamusPlugins);
    }

    @Override
    public void onResume() {
        super.onResume();
        manager().execute("list", "", this::received);
    }

    private void received(JSONObject result) {
        if (isFinished) return;
        loading = false;
        error = result.optString("error");
        JSONArray updated = result.optJSONArray("plugins");
        if (updated != null) plugins = updated;
        if (listView != null) listView.adapter.update(true);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(IMPORT, R.drawable.msg_add, getString(R.string.CalamusPluginImport)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(DISABLE_ALL, getString(R.string.CalamusPluginsDisableAll)));
        items.add(UItem.asShadow(null));
        if (!error.isEmpty()) items.add(UItem.asShadow(error));
        items.add(UItem.asCenteredHeader(getString(R.string.CalamusPluginsInstalled)));
        if (loading) {
            items.add(UItem.asShadow(getString(R.string.Loading)));
        } else if (plugins.length() == 0) {
            items.add(UItem.asShadow(getString(R.string.CalamusPluginsEmpty)));
        }
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.optJSONObject(i);
            items.add(UItem.asButton(100 + i, plugin.optString("name"),
                    getString(!plugin.optString("error").isEmpty() ? R.string.CalamusPluginError :
                            plugin.optBoolean("enabled") ? R.string.CalamusPluginEnabled : R.string.CalamusPluginDisabled)));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == IMPORT) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(intent, PICK_PLUGIN);
            } catch (android.content.ActivityNotFoundException failure) {
                error = getString(R.string.CalamusPluginPickerMissing);
                listView.adapter.update(true);
            }

        } else if (item.id == DISABLE_ALL) {
            manager().execute("disable_all", "", this::received);
        } else if (item.id >= 100 && item.id - 100 < plugins.length()) {
            showPlugin(plugins.optJSONObject(item.id - 100));
        }
    }

    private void reviewReady(JSONObject result) {
        if (isFinished) {
            JSONObject review = result.optJSONObject("review");
            if (review != null) manager().execute("review_cancel", review.optString("token"), null);
            return;
        }
        error = result.optString("error");
        JSONObject review = result.optJSONObject("review");
        if (review != null && error.isEmpty()) presentFragment(new PluginInstallActivity(review.optString("token")));
        if (listView != null) listView.adapter.update(true);
    }

    private void showPlugin(JSONObject plugin) {
        if (getParentActivity() == null) return;
        String id = plugin.optString("id");
        String details = plugin.optString("description") + "\n\n" + id + " · " + plugin.optString("version")
                + "\n" + plugin.optString("author") + "\nAPI " + plugin.optInt("api_version")
                + "\n" + getString(R.string.CalamusPluginCapabilities) + ": " + plugin.optJSONArray("permissions");
        if (!plugin.optString("error").isEmpty()) details += "\n\n" + plugin.optString("error");
        JSONArray logs = plugin.optJSONArray("logs");
        if (logs != null && logs.length() > 0) {
            details += "\n\n" + getString(R.string.CalamusPluginLog);
            for (int i = Math.max(0, logs.length() - 5); i < logs.length(); i++) details += "\n" + logs.optString(i);
        }
        boolean enabled = plugin.optBoolean("enabled");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(plugin.optString("name"));
        builder.setMessage(details);
        if (PluginGhostSettings.supports(plugin, "ghost")) {
            TextCheckCell ghost = new TextCheckCell(getParentActivity(), 21, true, getResourceProvider());
            updateGhostControl(ghost, plugin);
            ghost.setBackground(Theme.getSelectorDrawable(false));
            ghost.setOnClickListener(view -> {
                ghost.setEnabled(false);
                try {
                    JSONObject value = new JSONObject().put("plugin_id", id).put("key", "ghost").put("value", !PluginGhostSettings.checked(plugin));
                    manager().execute("setting", value.toString(), result -> {
                        received(result);
                        JSONObject current = plugin; JSONArray updated = result.optJSONArray("plugins");
                        if (updated != null) for (int i = 0; i < updated.length(); i++) if (id.equals(updated.optJSONObject(i).optString("id"))) current = updated.optJSONObject(i);
                        updateGhostControl(ghost, current);
                    });
                } catch (Exception failure) { updateGhostControl(ghost, plugin); }
            });
            android.widget.LinearLayout content = new android.widget.LinearLayout(getParentActivity());
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            content.addView(ghost, new android.widget.LinearLayout.LayoutParams(-1, -2));
            android.widget.TextView information = new android.widget.TextView(getParentActivity());
            information.setText(details); information.setTextSize(16);
            information.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
            int padding = org.telegram.messenger.AndroidUtilities.dp(21);
            information.setPadding(padding, org.telegram.messenger.AndroidUtilities.dp(12), padding, org.telegram.messenger.AndroidUtilities.dp(12));
            content.addView(information, new android.widget.LinearLayout.LayoutParams(-1, -2));
            builder.setMessage(null); builder.setView(content);
        }
        builder.setPositiveButton(getString(enabled ? R.string.CalamusPluginDisable : R.string.CalamusPluginEnable),
                (dialog, which) -> {
                    if (enabled) manager().execute("disable", id, this::received);
                    else if (plugin.optBoolean("access_granted") && !plugin.optString("error").contains("Permission was not granted")) manager().execute("enable", id, this::received);
                    else manager().execute("review_installed", id, this::reviewReady);
                });
        builder.setNegativeButton(getString(R.string.Close), null);
        builder.setNeutralButton(getString(R.string.Settings), (dialog, which) -> {
            CharSequence[] actions = {getString(R.string.CalamusPluginSettings), getString(R.string.CalamusPluginAccess), getString(R.string.Delete)};
            AlertDialog.Builder options = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            options.setTitle(plugin.optString("name"));
            options.setItems(actions, (d, index) -> {
                if (index == 0) presentFragment(new PluginSettingsActivity(id));
                else if (index == 1) presentFragment(new PluginAccessActivity(id));
                else confirmDelete(id);
            });
            showDialog(options.create());
        });
        showDialog(builder.create());
    }
    private void updateGhostControl(TextCheckCell cell, JSONObject plugin) {
        boolean available = PluginGhostSettings.available(plugin);
        cell.setTextAndValueAndCheck(getString(R.string.CalamusGhostMode), getString(available ? R.string.CalamusGhostAllContacts : R.string.CalamusGhostRequiresAccess), PluginGhostSettings.checked(plugin), true, false);
        cell.setEnabled(available); cell.setAlpha(available ? 1f : 0.5f);
    }

    private void confirmDelete(String id) {
            AlertDialog.Builder confirm = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            confirm.setTitle(getString(R.string.Delete));
            confirm.setMessage(getString(R.string.CalamusPluginDeleteConfirm));
            confirm.setPositiveButton(getString(R.string.Delete), (d, w) -> manager().execute("remove", id, this::received));
            confirm.setNegativeButton(getString(R.string.Cancel), null);
            showDialog(confirm.create());
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == PICK_PLUGIN && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            manager().reviewPackage(data.getData(), this::reviewReady);
        }
    }
}
