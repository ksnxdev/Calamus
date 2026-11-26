package ksnxdev.calamus.extera.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import java.util.ArrayList;
import ksnxdev.calamus.extera.ExteraPluginManager;
import uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry;

public final class ExteraPluginsActivity extends BaseCGPreferencesEntry {
    private static final int HELP = 71;
    private static final int IMPORT = 1;
    private static final int DISABLE = 2;
    private static final int UPSTREAM = 3;
    private static final int PICK = 2916;
    private JSONArray plugins = new JSONArray();
    private String error = "";
    private boolean loading = true;
    private ExteraWarningDialog warning;
    private Uri pendingImport;
    public ExteraPluginsActivity() {}
    public ExteraPluginsActivity(Uri uri) { pendingImport = uri; }
    private ExteraPluginManager manager() { return ExteraPluginManager.getInstance(); }
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.CalamusExteraPlugins); }
    @Override public View createView(Context context) {
        View root = super.createView(context);
        actionBar.createMenu().addItem(HELP, "?").setContentDescription(LocaleController.getString(R.string.CalamusExteraWarningTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) { if (warning == null || !warning.isLocked()) finishFragment(); }
                else if (id == HELP && ExteraPluginManager.isWarningAccepted()) showWarning(false);
            }
        });
        return root;
    }
    @Override public void onResume() {
        super.onResume();
        if (!ExteraPluginManager.isWarningAccepted()) {
            if (warning == null || !warning.isShowing()) showWarning(true);
        } else refresh();
    }
    private void showWarning(boolean first) {
        if (getParentActivity() == null || warning != null && warning.isShowing()) return;
        warning = new ExteraWarningDialog(getParentActivity(), getResourceProvider(), first, () -> {
            if (first) {
                ExteraPluginManager.acceptWarning();
                if (!isFinished) refresh();
            }
        });
        showDialog(warning);
    }
    private void refresh() {
        manager().list(result -> {
            if (isFinished) return;
            loading = false;
            error = result.optString("error");
            JSONArray list = result.optJSONArray("plugins");
            if (list != null) plugins = list;
            if (listView != null) listView.adapter.update(true);
            if (pendingImport != null && error.isEmpty()) {
                Uri uri = pendingImport;
                pendingImport = null;
                review(uri);
            }
        });
    }
    @Override public boolean onBackPressed(boolean invoked) {
        return warning != null && warning.isLocked() ? false : super.onBackPressed(invoked);
    }
    @Override public boolean isSwipeBackEnabled(MotionEvent event) {
        return (warning == null || !warning.isShowing()) && super.isSwipeBackEnabled(event);
    }
    @Override public void onFragmentDestroy() {
        if (warning != null) warning.dismissForDestroy();
        super.onFragmentDestroy();
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        UItem install = UItem.asButton(IMPORT, R.drawable.msg_add, LocaleController.getString(R.string.CalamusPluginImport));
        install.enabled = ExteraPluginManager.isWarningAccepted();
        items.add(install);
        items.add(UItem.asButton(DISABLE, LocaleController.getString(R.string.CalamusExteraDisableAll)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asCenteredHeader(LocaleController.getString(R.string.CalamusExteraInstalled)));
        if (!error.isEmpty()) items.add(UItem.asShadow(error));
        else if (loading) items.add(UItem.asShadow(LocaleController.getString(R.string.Loading)));
        else if (plugins.length() == 0) items.add(UItem.asShadow(LocaleController.getString(R.string.CalamusExteraEmpty)));
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject p = plugins.optJSONObject(i);
            items.add(UItem.asButton(100 + i, p.optString("name"), LocaleController.getString(!p.optString("error").isEmpty() ? R.string.CalamusPluginError : p.optBoolean("enabled") ? R.string.CalamusPluginEnabled : R.string.CalamusPluginDisabled)));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(UPSTREAM, LocaleController.getString(R.string.CalamusExteraAttribution)));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!ExteraPluginManager.isWarningAccepted()) return;
        if (item.id == IMPORT) {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(pick, PICK); }
            catch (android.content.ActivityNotFoundException e) { error = LocaleController.getString(R.string.CalamusPluginPickerMissing); listView.adapter.update(true); }
        } else if (item.id == DISABLE) manager().disableAll(this::received);
        else if (item.id == UPSTREAM) Browser.openUrl(getContext(), "https://github.com/exteraSquad/exteraGram");
        else if (item.id >= 100 && item.id - 100 < plugins.length()) showPlugin(plugins.optJSONObject(item.id - 100));
    }
    private void received(JSONObject result) {
        if (isFinished) return;
        error = result.optString("error");
        refresh();
    }
    @Override public void onActivityResultFragment(int request, int result, Intent data) {
        super.onActivityResultFragment(request, result, data);
        if (request == PICK && result == Activity.RESULT_OK && data != null && data.getData() != null) review(data.getData());
    }
    private void review(Uri uri) {
        manager().review(uri, proposal -> {
            if (isFinished) { manager().cancelReview(proposal.optString("token")); return; }
            error = proposal.optString("error");
            if (!error.isEmpty()) { listView.adapter.update(true); return; }
            AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            dialog.setTitle(proposal.optString("name"));
            dialog.setMessage(proposal.optString("description") + "\n\n" + proposal.optString("id") + " · " + proposal.optString("version") + "\n" + proposal.optString("author"));
            boolean[] confirmed = {false};
            dialog.setPositiveButton(LocaleController.getString(proposal.optBoolean("update") ? R.string.CalamusPluginApproveUpdate : R.string.CalamusPluginApproveInstall), (d, w) -> {
                confirmed[0] = true;
                manager().install(proposal.optString("token"), this::received);
            });
            dialog.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            dialog.setOnDismissListener(d -> { if (!confirmed[0]) manager().cancelReview(proposal.optString("token")); });
            showDialog(dialog.create());
        });
    }
    private void showPlugin(JSONObject plugin) {
        String id = plugin.optString("id");
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        dialog.setTitle(plugin.optString("name"));
        dialog.setMessage(plugin.optString("description") + "\n\n" + id + " · " + plugin.optString("version") + "\n" + plugin.optString("author") + (plugin.optString("error").isEmpty() ? "" : "\n\n" + plugin.optString("error")));
        dialog.setPositiveButton(LocaleController.getString(plugin.optBoolean("enabled") ? R.string.CalamusPluginDisable : R.string.CalamusPluginEnable), (d, w) -> manager().setEnabled(id, !plugin.optBoolean("enabled"), this::received));
        dialog.setNegativeButton(LocaleController.getString(R.string.Close), null);
        dialog.setNeutralButton(LocaleController.getString(R.string.Settings), (d, w) -> {
            AlertDialog.Builder options = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            options.setItems(new CharSequence[]{LocaleController.getString(R.string.CalamusPluginSettings), LocaleController.getString(R.string.Delete)}, (a, index) -> {
                if (index == 0) presentFragment(new ExteraPluginSettingsActivity(id));
                else {
                    AlertDialog.Builder confirm = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                    confirm.setTitle(LocaleController.getString(R.string.Delete));
                    confirm.setMessage(plugin.optString("name"));
                    confirm.setPositiveButton(LocaleController.getString(R.string.Delete), (c, q) -> manager().remove(id, this::received));
                    confirm.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                    showDialog(confirm.create());
                }
            });
            showDialog(options.create());
        });
        showDialog(dialog.create());
    }
}
