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

public final class PluginInstallActivity extends BaseCGPreferencesEntry {
    private final String token;
    private JSONObject proposal = new JSONObject();
    private JSONArray choices = new JSONArray();
    private final Set<Integer> selected = new HashSet<>();
    private boolean loaded;
    private boolean busy;
    private String error = "";
    public PluginInstallActivity(String token) { this.token = token; }
    private PluginManager manager() { return PluginManager.getInstance(ApplicationLoader.applicationContext); }
    @Override protected CharSequence getTitle() { return getString(R.string.CalamusPluginInstallation); }
    @Override public void onResume() {
        super.onResume();
        if (loaded) return;
        manager().execute("review_get", token, result -> {
            if (isFinished) return;
            error = errorText(result);
            JSONObject value = result.optJSONObject("review");
            if (value != null && error.isEmpty()) {
                proposal = value; loaded = true;
                if (needsAccount()) choices = TelegramPluginHost.accessOptions(proposal.optJSONArray("scopes"));
                JSONArray previous = proposal.optJSONArray("scopes");
                if (previous != null) for (int i = 0; i < choices.length(); i++) for (int j = 0; j < previous.length(); j++) if (sameScope(choices.optJSONObject(i), previous.optJSONObject(j))) selected.add(i);
                for (int i = 0; i < choices.length(); i++) if (choices.optJSONObject(i).optLong("dialog_id") == 0 && choices.optJSONObject(i).optInt("account_id") == org.telegram.messenger.UserConfig.selectedAccount) selected.add(i);
            }
            if (listView != null) { listView.adapter.update(false); listView.scrollToPosition(0); }
        });
    }
    private static boolean sameScope(JSONObject a, JSONObject b) {
        return a != null && b != null && a.optInt("account_id", -1) == b.optInt("account_id", -2) && a.optLong("user_id", -1) == b.optLong("user_id", -2) && a.optLong("dialog_id", 0) == b.optLong("dialog_id", -1);
    }
    private boolean needsAccount() {
        JSONArray permissions = proposal.optJSONArray("permissions");
        if (permissions != null) for (int i = 0; i < permissions.length(); i++) if (Set.of("accounts.read", "chats.read", "messages.read", "messages.send", "ui", "messages.archive", "messages.menu", "presence.control", "presence.read").contains(permissions.optString(i))) return true;
        return false;
    }
    private static String errorText(JSONObject result) {
        return result.optString("error_code").isEmpty() ? result.optString("error") : getString(R.string.CalamusPluginReviewExpired);
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(proposal.optString("name", getString(R.string.CalamusPlugins))));
        if (loaded) {
            items.add(UItem.asShadow(proposal.optString("version") + " · " + proposal.optString("author") + "\n" + proposal.optString("id")));
            items.add(UItem.asHeader(getString(R.string.CalamusPluginRequestedPermissions)));
            JSONArray permissions = proposal.optJSONArray("permissions");
            ArrayList<String> lines = new ArrayList<>();
            if (permissions != null) for (int i = 0; i < permissions.length(); i++) lines.add("• " + PluginAccessActivity.permissionTitle(permissions.optString(i)));
            items.add(UItem.asShadow(lines.isEmpty() ? getString(R.string.CalamusPluginNoPermissions) : android.text.TextUtils.join("\n", lines)));
        }
        UItem install = UItem.asButton(1, getString(busy ? R.string.Loading : proposal.optBoolean("update") ? R.string.CalamusPluginApproveUpdate : R.string.CalamusPluginApproveInstall));
        install.enabled = loaded && !busy; items.add(install);
        UItem cancel = UItem.asButton(2, getString(R.string.Cancel)); cancel.enabled = !busy; items.add(cancel);
        items.add(UItem.asShadow(getString(R.string.CalamusPluginInstallConsent)));
        if (!error.isEmpty()) items.add(UItem.asShadow(error));
        if (loaded && needsAccount()) {
            items.add(UItem.asHeader(getString(R.string.CalamusPluginAllowedAccounts)));
            items.add(UItem.asShadow(getString(R.string.CalamusPluginInstallScopeHint)));
            if (choices.length() == 0) items.add(UItem.asShadow(getString(R.string.CalamusPluginNoAccounts)));
            boolean chatsHeader = false;
            for (int i = 0; i < choices.length(); i++) {
                if (!chatsHeader && choices.optJSONObject(i).optLong("dialog_id") != 0) { items.add(UItem.asHeader(getString(R.string.CalamusPluginAllowedChats))); items.add(UItem.asButton(3, getString(allChatsSelected() ? R.string.DeselectAll : R.string.SelectAll))); chatsHeader = true; }
                UItem scope = SettingsHelper.asSwitchCG(1000 + i, choices.optJSONObject(i).optString("title")).setChecked(selected.contains(i));
                scope.enabled = !busy; items.add(scope);
            }
            items.add(UItem.asShadow(null));
        }
    }
    private boolean allChatsSelected() {
        boolean any = false;
        for (int i = 0; i < choices.length(); i++) if (choices.optJSONObject(i).optLong("dialog_id") != 0) { any = true; if (!selected.contains(i)) return false; }
        return any;
    }
    private void toggleChats() {
        boolean clear = allChatsSelected();
        for (int i = 0; i < choices.length(); i++) if (choices.optJSONObject(i).optLong("dialog_id") != 0) { if (clear) selected.remove(i); else selected.add(i); }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (busy) return;
        if (item.id == 2) { finishFragment(); return; }
        if (!loaded) return;
        if (item.id == 3) {
            toggleChats();
            error = "";
        } else if (item.id >= 1000) {
            int index = item.id - 1000;
            if (!selected.remove(index)) {
                if (selected.size() >= ksnxdev.calamus.plugins.PluginAccessPolicy.MAX_CHAT_SCOPES) error = getString(R.string.CalamusPluginScopeLimit);
                else { selected.add(index); error = ""; }
            }
        } else if (item.id == 1) {
            try {
                JSONArray scopes = new JSONArray(); JSONArray current = needsAccount() ? TelegramPluginHost.accessOptions(choices) : new JSONArray();
                for (int index : selected) {
                    JSONObject chosen = choices.getJSONObject(index); boolean active = false;
                    for (int i = 0; i < current.length(); i++) active |= sameScope(chosen, current.optJSONObject(i));
                    if (!active) { error = getString(R.string.CalamusPluginAccountChanged); listView.adapter.update(true); return; }
                    scopes.put(chosen);
                }
                busy = true;
                JSONObject consent = new JSONObject().put("token", token).put("digest", proposal.getString("digest")).put("scopes", scopes);
                manager().execute("review_confirm", consent.toString(), result -> {
                    if (isFinished) return;
                    busy = false; error = errorText(result);
                    if (error.isEmpty()) finishFragment(); else if (listView != null) listView.adapter.update(true);
                });
            } catch (Exception failure) { busy = false; error = failure.getMessage(); }
        }
        listView.adapter.update(item.id != 3);
    }
    @Override public void onFragmentDestroy() {
        manager().execute("review_cancel", token, null);
        super.onFragmentDestroy();
    }
}
