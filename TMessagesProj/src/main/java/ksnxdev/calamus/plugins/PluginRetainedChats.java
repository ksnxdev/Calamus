package ksnxdev.calamus.plugins;

import android.util.LongSparseArray;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.HashSet;

public final class PluginRetainedChats {
    private static final PluginRetainedChats[] instances = new PluginRetainedChats[UserConfig.MAX_ACCOUNT_COUNT];
    private final int account;
    private long identity;
    private int generation;
    private final LongSparseArray<JSONObject> retained = new LongSparseArray<>();
    private final LongSparseArray<TLRPC.Dialog> injected = new LongSparseArray<>();
    private final LongSparseArray<Long> revisions = new LongSparseArray<>();
    private final HashSet<Long> checking = new HashSet<>();
    private final LongSparseArray<JSONObject> pending = new LongSparseArray<>();
    private final HashSet<Long> hidden = new HashSet<>();
    private final LongSparseArray<Runnable> activity = new LongSparseArray<>();
    private final org.telegram.messenger.support.LongSparseIntArray latestMessages = new org.telegram.messenger.support.LongSparseIntArray();

    private PluginRetainedChats(int account) { this.account = account; identity = user(); }
    private long user() { return UserConfig.getInstance(account).getClientUserId(); }
    private PluginManager manager() { return PluginManager.getInstance(ApplicationLoader.applicationContext); }
    public static PluginRetainedChats getInstance(int account) {
        if (instances[account] == null) instances[account] = new PluginRetainedChats(account);
        return instances[account];
    }
    private void checkIdentity() {
        if (identity == user()) return;
        for (int i = 0; i < activity.size(); i++) AndroidUtilities.cancelRunOnUIThread(activity.valueAt(i));
        activity.clear(); latestMessages.clear();
        removeInjected(); retained.clear(); hidden.clear(); checking.clear(); pending.clear(); revisions.clear(); identity = user(); generation++;
    }
    private long revision(long dialog) { Long value = revisions.get(dialog); return value == null ? 0 : value; }
    private void changed(long dialog) { revisions.put(dialog, revision(dialog) + 1); }
    public boolean isDeleted(long dialog) {
        checkIdentity();
        JSONObject row = retained.get(dialog);
        return row != null && !hidden.contains(dialog) && manager().retainedAllowed(row.optString("_plugin"), row.optString("_session"), TelegramPluginHost.chatContext(account, dialog));
    }
    public void remoteDeleted(ArrayList<Integer> ids) {
        checkIdentity(); if (identity <= 0 || ids.isEmpty()) return;
        long expectedUser = identity;
        if (ids.size() > 1000) {
            for (int start = 0; start < ids.size(); start += 1000) remoteDeleted(new ArrayList<>(ids.subList(start, Math.min(ids.size(), start + 1000))));
            return;
        }
        JSONArray messages = new JSONArray(); for (int id : ids) messages.put(id);
        manager().remoteDeletedChats(TelegramPluginHost.chatContext(account, 0), messages, result -> {
            if (user() != expectedUser) return;
            JSONArray rows = result.optJSONArray("rows");
            if (rows != null) for (int i = 0; i < rows.length(); i++) checkRemote(rows.optJSONObject(i));
        });
    }
    private void checkRemote(JSONObject scope) {
        if (scope == null) return;
        long dialog = scope.optLong("dialog_id"), expectedUser = user(), expectedRevision = revision(dialog);
        if (hidden.contains(dialog)) return;
        if (!checking.add(dialog)) { pending.put(dialog, scope); return; }
        if (!manager().authorizeApiNow(scope.optString("_plugin"), scope.optString("_session"), "archive.query", scope)) { checking.remove(dialog); return; }
        TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = MessagesController.getInstance(account).getInputPeer(dialog); request.limit = 1;
        if (request.peer == null || request.peer instanceof TLRPC.TL_inputPeerEmpty) { checking.remove(dialog); return; }
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (user() != expectedUser) return;
            checking.remove(dialog);
            if (user() == expectedUser && revision(dialog) == expectedRevision && !hidden.contains(dialog) && error == null && response instanceof TLRPC.messages_Messages && emptyHistory((TLRPC.messages_Messages) response)) manager().confirmDeletedChat(scope);
            JSONObject next = pending.get(dialog); pending.remove(dialog);
            if (next != null && user() == expectedUser) checkRemote(next);
        }));
    }
    private static boolean emptyHistory(TLRPC.messages_Messages history) {
        if (history instanceof TLRPC.TL_messages_messagesNotModified || history.messages.isEmpty() && history.count > 0) return false;
        for (TLRPC.Message message : history.messages) if (!(message.action instanceof TLRPC.TL_messageActionHistoryClear)) return false;
        return true;
    }
    public void forget(long dialog) {
        checkIdentity(); if (dialog <= 0 || dialog == identity) return;
        changed(dialog); hidden.add(dialog); pending.remove(dialog); retained.remove(dialog); generation++;
        Runnable task = activity.get(dialog); if (task != null) AndroidUtilities.cancelRunOnUIThread(task);
        activity.remove(dialog); latestMessages.delete(dialog);
        TLRPC.Dialog old = injected.get(dialog); injected.remove(dialog); removeInjected(old);
        manager().retainedChatActivity(TelegramPluginHost.chatContext(account, dialog), 0, true);
        notifyChanged(dialog);
    }
    public void newMessage(long dialog, int id) {
        checkIdentity(); if (dialog <= 0 || dialog == identity || id <= 0) return;
        changed(dialog);
        JSONObject row = retained.get(dialog);
        if (row == null || id > row.optInt("last_id")) {
            boolean visible = row != null || hidden.contains(dialog);
            hidden.remove(dialog); retained.remove(dialog); if (visible) generation++;
            TLRPC.Dialog old = injected.get(dialog); injected.remove(dialog); removeInjected(old);
            latestMessages.put(dialog, Math.max(id, latestMessages.get(dialog)));
            if (activity.get(dialog) == null) {
                long expectedUser = identity;
                Runnable task = () -> {
                    int latest = latestMessages.get(dialog); latestMessages.delete(dialog); activity.remove(dialog);
                    if (user() == expectedUser) manager().retainedChatActivity(TelegramPluginHost.chatContext(account, dialog), latest, false);
                };
                activity.put(dialog, task); AndroidUtilities.runOnUIThread(task, 100);
            }
            if (row != null) notifyChanged(dialog);
        }
    }
    public void refresh() {
        checkIdentity();
        int expectedGeneration = ++generation; long expectedUser = identity;
        if (expectedUser <= 0) return;
        manager().retainedChats(TelegramPluginHost.chatContext(account, 0), result -> {
            if (generation != expectedGeneration || user() != expectedUser) return;
            JSONArray rows = result.optJSONArray("rows");
            if (rows == null) return;
            ArrayList<TLRPC.User> users = new ArrayList<>();
            MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
                for (int i = 0; i < rows.length(); i++) {
                    TLRPC.User peer = MessagesStorage.getInstance(account).getUser(rows.optJSONObject(i).optLong("dialog_id"));
                    if (peer != null) users.add(peer);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation != expectedGeneration || user() != expectedUser) return;
                    removeInjected(); retained.clear();
                    MessagesController.getInstance(account).putUsers(users, true);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i); long dialog = row.optLong("dialog_id");
                        if (!hidden.contains(dialog) && manager().retainedAllowed(row.optString("_plugin"), row.optString("_session"), TelegramPluginHost.chatContext(account, dialog))) {
                            JSONObject previous = retained.get(dialog);
                            if (previous == null || previous.optInt("last_id") < row.optInt("last_id")) retained.put(dialog, row);
                        }
                    }
                    MessagesController.getInstance(account).sortDialogs(null);
                    notifyChanged(0);
                });
            });
        });
    }
    public void apply(MessagesController controller, androidx.collection.LongSparseArray<TLRPC.Dialog> removing) {
        checkIdentity();
        for (int i = 0; i < retained.size(); i++) {
            long dialog = retained.keyAt(i); JSONObject row = retained.valueAt(i);
            if (!isDeleted(dialog) || controller.getUser(dialog) == null || removing.get(dialog) != null) continue;
            ArrayList<MessageObject> current = controller.dialogMessage.get(dialog);
            boolean live = false;
            if (current != null) for (MessageObject message : current) if (!message.calamusDeleted && !message.deleted && !(message.messageOwner.action instanceof TLRPC.TL_messageActionHistoryClear) && (message.getId() < 0 || message.getId() > row.optInt("last_id"))) { live = true; break; }
            if (live) continue;
            TLRPC.Dialog old = controller.dialogs_dict.get(dialog), local = injected.get(dialog);
            if (local == null) {
                TLRPC.TL_dialog value = new TLRPC.TL_dialog(); value.id = dialog; value.peer = controller.getPeer(dialog);
                value.last_message_date = row.optInt("date"); value.top_message = 0;
                if (old != null) { value.folder_id = old.folder_id; value.notify_settings = old.notify_settings; }
                if (value.notify_settings == null) value.notify_settings = new TLRPC.TL_peerNotifySettings();
                local = value; injected.put(dialog, local);
            }
            if (old != null && old != local) controller.getAllDialogs().remove(old);
            controller.dialogs_dict.put(dialog, local);
            if (!controller.getAllDialogs().contains(local)) controller.getAllDialogs().add(local);
            MessageObject preview = TelegramPluginHost.archivedMessage(account, dialog, identity, row);
            if (preview != null) { ArrayList<MessageObject> list = new ArrayList<>(); list.add(preview); controller.dialogMessage.put(dialog, list); }
        }
    }
    private void removeInjected(TLRPC.Dialog dialog) {
        if (dialog == null) return;
        MessagesController controller = MessagesController.getInstance(account);
        if (controller.dialogs_dict.get(dialog.id) != dialog) { controller.getAllDialogs().remove(dialog); return; }
        ArrayList<MessageObject> messages = controller.dialogMessage.get(dialog.id);
        if (dialog.top_message != 0 || messages != null && !messages.isEmpty() && !messages.get(0).calamusDeleted) return;
        controller.getAllDialogs().remove(dialog);
        controller.dialogs_dict.remove(dialog.id);
        if (messages != null && !messages.isEmpty() && messages.get(0).calamusDeleted) controller.dialogMessage.remove(dialog.id);
    }
    private void removeInjected() { for (int i = 0; i < injected.size(); i++) removeInjected(injected.valueAt(i)); injected.clear(); }
    private void notifyChanged(long dialog) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.calamusArchiveChanged, dialog);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }
}
