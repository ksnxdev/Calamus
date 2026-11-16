package ksnxdev.calamus.plugins.ui;

import static org.telegram.messenger.LocaleController.getString;
import android.view.View;
import android.content.Context;
import android.widget.EditText;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry;
import uz.unnarsx.cherrygram.preferences.helpers.SettingsHelper;
import ksnxdev.calamus.plugins.PluginManager;

public final class PluginArchiveActivity extends BaseCGPreferencesEntry {
    private final String plugin, session;
    private final JSONObject scope;
    private JSONArray rows = new JSONArray();
    private int offset;
    private String error = "";
    private String search = "";
    private int loadSequence;
    private final Runnable searchRunnable = this::load;
    public PluginArchiveActivity(String plugin, String session, JSONObject scope) {
        this.plugin = plugin; this.session = session;
        try { this.scope = new JSONObject(scope.toString()); } catch (Exception failure) { throw new IllegalArgumentException(failure); }
    }
    private PluginManager manager() { return PluginManager.getInstance(ApplicationLoader.applicationContext); }
    @Override public View createView(Context context) {
        View view = super.createView(context);
        if (scope.optInt("message_id") <= 0) {
            searchItem = actionBar.createMenu().addItem(975004, R.drawable.outline_header_search).setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override public void onSearchCollapse() { search = ""; offset = 0; load(); }
                        @Override public void onTextChanged(EditText field) {
                            search = field.getText().toString().trim(); offset = 0; loadSequence++;
                            AndroidUtilities.cancelRunOnUIThread(searchRunnable); AndroidUtilities.runOnUIThread(searchRunnable, 250);
                        }
                    });
            searchItem.setSearchFieldHint(getString(R.string.CalamusSearchDeleted));
            searchItem.getSearchField().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        }
        return view;
    }
    @Override public void onFragmentDestroy() { loadSequence++; AndroidUtilities.cancelRunOnUIThread(searchRunnable); super.onFragmentDestroy(); }
    @Override protected CharSequence getTitle() {
        return getString(scope.optInt("message_id") > 0 ? R.string.CalamusMessageHistory : scope.optBoolean("deleted_only") ? R.string.CalamusDeletedMessages : R.string.CalamusLocalArchive);
    }
    private boolean allowed() {
        int account = scope.optInt("account_id", -1);
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(account).isClientActivated()
                && UserConfig.getInstance(account).getClientUserId() == scope.optLong("user_id")
                && manager().authorizeApiNow(plugin, session, "archive.query", scope);
    }
    @Override public void onResume() { super.onResume(); load(); }
    private void load() {
        final int sequence = ++loadSequence;
        if (!allowed()) { rows = new JSONArray(); error = getString(R.string.CalamusArchiveAccessRevoked); updateRows(false); return; }
        try {
            JSONObject args = new JSONObject(scope.toString()).put("offset", offset).put("limit", 20).put("search", search);
            args.remove("version_id");
            manager().archiveRequest(plugin, session, "archive.query", args, (result, failure) -> {
                if (isFinished || sequence != loadSequence) return;
                if (!allowed()) { rows = new JSONArray(); error = getString(R.string.CalamusArchiveAccessRevoked); }
                else if (!failure.isEmpty()) { rows = new JSONArray(); error = failure; }
                else { rows = ((JSONObject) result).optJSONArray("rows"); if (rows == null) rows = new JSONArray(); error = ""; }
                updateRows(false);
            });
        } catch (Exception failure) { error = failure.getMessage(); updateRows(false); }
    }
    private String date(JSONObject row) {
        long timestamp = row.optLong("edit_date");
        if (timestamp <= 0) timestamp = row.optLong("date");
        if (timestamp <= 0) timestamp = row.optLong("captured") / 1000;
        return LocaleController.formatDateTime(timestamp, true);
    }
    @Override protected void fillItems(java.util.ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(manager().pluginName(plugin)));
        items.add(UItem.asShadow(getString(R.string.CalamusArchiveCaptureNotice)));
        if (!error.isEmpty()) items.add(UItem.asShadow(error));
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); String text = row.optString("text").replace('\n', ' ');
            if (text.isEmpty()) text = "[" + row.optString("media_type", "media") + "]";
            if (text.codePointCount(0, text.length()) > 90) text = text.substring(0, text.offsetByCodePoints(0, 90)) + "…";
            String value = getString(row.optInt("edit_date") > 0 ? R.string.CalamusArchiveEditedVersion : R.string.CalamusArchiveOriginalVersion) + " · " + date(row) + (row.optInt("deleted") != 0 ? " · " + getString(R.string.CalamusArchiveDeleted) : "");
            items.add(SettingsHelper.asTextDetail(100 + i, 0, text, value));
        }
        if (rows.length() == 0 && error.isEmpty()) items.add(UItem.asShadow(getString(R.string.CalamusArchiveEmpty)));
        items.add(UItem.asShadow(null));
        if (offset > 0) items.add(UItem.asButton(1, getString(R.string.CalamusArchivePrevious)));
        if (rows.length() == 20) items.add(UItem.asButton(2, getString(R.string.CalamusArchiveNext)));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!allowed()) { load(); return; }
        if (item.id == 1) { offset = Math.max(0, offset - 20); load(); }
        else if (item.id == 2) { offset += 20; load(); }
        else if (item.id >= 100 && item.id - 100 < rows.length()) {
            JSONObject row = rows.optJSONObject(item.id - 100);
            try {
                JSONObject args = new JSONObject(scope.toString());
                if (scope.optInt("message_id") <= 0) {
                    args.put("message_id", row.getInt("message_id")).put("deleted_only", false);
                    presentFragment(new PluginArchiveActivity(plugin, session, args));
                } else {
                    args.put("version_id", row.getLong("id"));
                    manager().archiveRequest(plugin, session, "archive.query", args, (result, failure) -> {
                        if (isFinished || !allowed()) return;
                        JSONArray found = result instanceof JSONObject ? ((JSONObject) result).optJSONArray("rows") : null;
                        if (!failure.isEmpty() || found == null || found.length() == 0) { error = failure; updateRows(false); return; }
                        JSONObject version = found.optJSONObject(0);
                        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                        dialog.setTitle(date(version)); dialog.setMessage(version.optString("text"));
                        dialog.setPositiveButton(getString(R.string.Close), null); showDialog(dialog.create());
                    });
                }
            } catch (Exception failure) { error = failure.getMessage(); updateRows(false); }
        }
    }
}
