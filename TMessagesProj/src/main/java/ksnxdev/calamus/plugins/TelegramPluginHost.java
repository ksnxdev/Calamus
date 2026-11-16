package ksnxdev.calamus.plugins;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import java.util.ArrayList;

public final class TelegramPluginHost implements PluginBridge.Client, NotificationCenter.NotificationCenterDelegate {
    private static TelegramPluginHost instance;
    private final PluginManager manager;
    private final long[] identities = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private TelegramPluginHost(Context context) {
        manager = PluginManager.getInstance(context);
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            identities[account] = UserConfig.getInstance(account).getClientUserId();
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(this, NotificationCenter.didReceiveNewMessages);
            center.addObserver(this, NotificationCenter.messageReceivedByServer);
            center.addObserver(this, NotificationCenter.messageSendError);
            center.addObserver(this, NotificationCenter.messagesDeleted);
            center.addObserver(this, NotificationCenter.replaceMessagesObjects);
            center.addObserver(this, NotificationCenter.appDidLogout);
        }
    }

    public static void install(Context context) {
        if (instance == null) instance = new TelegramPluginHost(context);
        PluginBridge.setClient(instance);
    }

    public static void refreshAccounts() {
        if (instance == null) return;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            long user = UserConfig.getInstance(account).getClientUserId();
            if (instance.identities[account] != user) {
                PluginPresenceController.logout(account);
                long previous = instance.identities[account];
                instance.identities[account] = user;
                try {
                    if (previous != 0) instance.manager.event("account.changed", new JSONObject().put("account_id", account).put("user_id", previous).put("logged_in", false));
                    if (user != 0) instance.manager.event("account.changed", new JSONObject().put("account_id", account).put("user_id", user).put("logged_in", true));
                } catch (Exception ignored) { }
            }
        }
    }

    public static JSONObject chatContext(int account, long dialogId) {
        JSONObject value = new JSONObject();
        try {
            value.put("account_id", account).put("user_id", UserConfig.getInstance(account).getClientUserId())
                    .put("dialog_id", dialogId);
        } catch (Exception ignored) {
        }
        return value;
    }

    public static boolean consumeCommand(int account, long dialogId, CharSequence text) {
        return !DialogObject.isEncryptedDialog(dialogId) && text != null && UserConfig.getInstance(account).isClientActivated()
                && PluginManager.getInstance(ApplicationLoader.applicationContext).dispatchCommand(text.toString(), chatContext(account, dialogId));
    }

    public static void chatOpened(int account, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) return;
        PluginManager.getInstance(ApplicationLoader.applicationContext).event("chat.opened", chatContext(account, dialogId));
    }

    public static void sending(int account, SendMessagesHelper.SendMessageParams params) {
        if (params.message == null || params.retryMessageObject != null || DialogObject.isEncryptedDialog(params.peer)) return;
        try {
            JSONObject value = chatContext(account, params.peer).put("text", params.message)
                    .put("scheduled", params.scheduleDate != 0);
            PluginManager.getInstance(ApplicationLoader.applicationContext).event("message.sending", value);
        } catch (Exception ignored) {
        }
    }

    public static boolean archivable(TLRPC.Message message) {
        return message != null && !DialogObject.isEncryptedDialog(MessageObject.getDialogId(message)) && message.ttl_period == 0 && (message.media == null || message.media.ttl_seconds == 0);
    }

    @Override public void revoked(String pluginId, String session) { PluginPresenceController.revoke(pluginId, session); }
    @Override public void archiveChanged(int account, long dialog) {
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.calamusArchiveChanged, dialog);
        else for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) NotificationCenter.getInstance(i).postNotificationName(NotificationCenter.calamusArchiveChanged, 0L);
    }

    private static PluginManager plugins() { return PluginManager.getInstance(ApplicationLoader.applicationContext); }
    public static boolean hasArchive(int account, long dialog) {
        return !DialogObject.isEncryptedDialog(dialog) && plugins().archiveProviders(chatContext(account, dialog)).length() > 0;
    }
    public static void showArchive(BaseFragment fragment, int account, long dialog, int messageId) {
        if (fragment.isFinished || fragment.getParentActivity() == null || DialogObject.isEncryptedDialog(dialog)) return;
        if (openArchive(fragment, account, dialog, messageId)) return;
        long user = UserConfig.getInstance(account).getClientUserId();
        plugins().execute("list", "", result -> {
            if (fragment.isFinished || fragment.getParentActivity() == null || UserConfig.getInstance(account).getClientUserId() != user) return;
            if (openArchive(fragment, account, dialog, messageId)) return;
            AlertDialog.Builder notice = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            notice.setTitle(org.telegram.messenger.LocaleController.getString(messageId > 0 ? org.telegram.messenger.R.string.CalamusMessageHistory : org.telegram.messenger.R.string.CalamusDeletedMessages));
            notice.setMessage(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.CalamusArchiveNeedsAccess));
            notice.setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.CalamusPlugins), (window, button) -> {
                ksnxdev.calamus.plugins.ui.PluginsActivity settings = new ksnxdev.calamus.plugins.ui.PluginsActivity(); settings.setCurrentAccount(account);
                fragment.presentFragment(settings);
            });
            notice.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Close), null);
            fragment.showDialog(notice.create());
        });
    }
    private static boolean openArchive(BaseFragment fragment, int account, long dialog, int messageId) {
        JSONArray providers = plugins().archiveProviders(chatContext(account, dialog));
        if (providers.length() == 0) return false;
        JSONObject provider = providers.optJSONObject(0);
        try { fragment.presentFragment(new ksnxdev.calamus.plugins.ui.PluginArchiveActivity(provider.getString("plugin"), provider.getString("session"), chatContext(account, dialog).put("deleted_only", messageId == 0).put("message_id", messageId))); return true; } catch (Exception ignored) { return false; }
    }
    public static boolean allowedDeleted(int account, long dialog, MessageObject message) {
        return message.calamusDeleted && plugins().authorizeApiNow(message.calamusArchivePlugin, message.calamusArchiveSession, "archive.query", chatContext(account, dialog));
    }
    public static void retainDeleted(int account, long dialog, long channel, ArrayList<MessageObject> messages) {
        JSONObject scope = chatContext(account, dialog);
        if (DialogObject.isEncryptedDialog(dialog) || !plugins().canRetainMessages(scope)) return;
        JSONArray providers = plugins().archiveProviders(scope), snapshots = new JSONArray();
        if (providers.length() == 0) return;
        JSONObject provider = providers.optJSONObject(0);
        for (MessageObject object : messages) try {
            if (object.getId() <= 0 || !archivable(object.messageOwner) || object.messageOwner.action != null && !(object.messageOwner.action instanceof TLRPC.TL_messageActionEmpty)) continue;
            snapshots.put(message(object.messageOwner));
            object.calamusDeleted = true; object.deleted = false; object.forceUpdate = true;
            object.calamusArchivePlugin = provider.getString("plugin"); object.calamusArchiveSession = provider.getString("session");
        } catch (Exception ignored) { }
        try { if (snapshots.length() > 0) plugins().retainDeleted(scope.put("channel_id", channel), snapshots); } catch (Exception ignored) { }
    }
    public static void deletedSnapshot(int account, long dialog, int minimum, int maximum, java.util.function.Consumer<ArrayList<MessageObject>> callback) {
        long user = UserConfig.getInstance(account).getClientUserId();
        plugins().deletedSnapshot(chatContext(account, dialog), minimum, maximum, result -> {
            ArrayList<MessageObject> objects = new ArrayList<>();
            if (UserConfig.getInstance(account).getClientUserId() != user || !UserConfig.getInstance(account).isClientActivated()) { callback.accept(objects); return; }
            JSONArray rows = result.optJSONArray("rows");
            if (rows != null) for (int i = 0; i < rows.length(); i++) try {
                JSONObject row = rows.getJSONObject(i); TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = row.getInt("message_id"); message.dialog_id = dialog; message.date = row.optInt("date"); message.edit_date = row.optInt("edit_date");
                message.out = row.optInt("outgoing") != 0; message.peer_id = MessagesController.getInstance(account).getPeer(dialog);
                long sender = row.optLong("sender_id"); if (sender == 0) sender = message.out ? user : dialog;
                message.from_id = MessagesController.getInstance(account).getPeer(sender); message.flags |= 256;
                message.message = row.optString("text"); if (message.message.isEmpty()) message.message = "[" + row.optString("media_type", "media") + "]";
                if (message.edit_date > 0) message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
                MessageObject object = new MessageObject(account, message, true, false); object.calamusDeleted = true;
                object.calamusArchivePlugin = row.getString("_plugin"); object.calamusArchiveSession = row.getString("_session"); objects.add(object);
            } catch (Exception ignored) { }
            callback.accept(objects);
        });
    }

    public static JSONObject message(TLRPC.Message message) throws Exception {
        return new JSONObject().put("id", message.id).put("dialog_id", MessageObject.getDialogId(message))
                .put("sender_id", message.from_id == null ? 0 : MessageObject.getPeerId(message.from_id))
                .put("text", message.message == null ? "" : message.message).put("date", message.date)
                .put("edit_date", message.edit_date).put("archivable", archivable(message))
                .put("channel_id", message.peer_id == null ? 0 : message.peer_id.channel_id)
                .put("media_type", message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty ? "" : message.media.getClass().getSimpleName().replace("TL_messageMedia", ""))
                .put("outgoing", message.out).put("has_media", message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        try {
            if (id == NotificationCenter.appDidLogout) {
                PluginPresenceController.logout(account);
                manager.event("account.changed", new JSONObject().put("account_id", account).put("user_id", identities[account]).put("logged_in", false));
                identities[account] = 0;
                return;
            }
            JSONObject value;
            if (id == NotificationCenter.messagesDeleted) {
                if (args.length < 3 || Boolean.TRUE.equals(args[2])) return;
                JSONArray ids = new JSONArray();
                for (Integer message : (ArrayList<Integer>) args[0]) {
                    ids.put(message);
                    if (ids.length() == 1000) {
                        manager.event("message.deleted", chatContext(account, 0).put("channel_id", ((Number) args[1]).longValue()).put("ids", ids));
                        ids = new JSONArray();
                    }
                }
                if (ids.length() > 0) manager.event("message.deleted", chatContext(account, 0).put("channel_id", ((Number) args[1]).longValue()).put("ids", ids));
                return;
            }
            if (id == NotificationCenter.messageSendError) {
                PluginPresenceController.endSend(account, ((Number) args[0]).intValue());
                manager.event("message.failed", chatContext(account, 0).put("id", args[0]));
                return;
            }
            if (id == NotificationCenter.messageReceivedByServer) {
                PluginPresenceController.endSend(account, ((Number) args[0]).intValue());
                if (DialogObject.isEncryptedDialog(((Number) args[3]).longValue())) return;
                value = chatContext(account, ((Number) args[3]).longValue()).put("old_id", args[0]).put("id", args[1]);
                if (args[2] instanceof TLRPC.Message && archivable((TLRPC.Message) args[2])) value.put("message", message((TLRPC.Message) args[2]));
                manager.event("message.sent", value);
            } else if (id == NotificationCenter.didReceiveNewMessages || id == NotificationCenter.replaceMessagesObjects) {
                long dialogId = ((Number) args[0]).longValue();
                if (DialogObject.isEncryptedDialog(dialogId)) return;
                JSONArray messages = new JSONArray();
                int eventBytes = 0;
                for (Object item : (ArrayList<?>) args[1]) {
                    if (item instanceof MessageObject) {
                        MessageObject object = (MessageObject) item;
                        if (!archivable(object.messageOwner) || id == NotificationCenter.didReceiveNewMessages && object.messageOwner.out) continue;
                        JSONObject snapshot = message(object.messageOwner); int bytes = snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (eventBytes + bytes > 20000 && messages.length() > 0) {
                            manager.event(id == NotificationCenter.didReceiveNewMessages ? "message.received" : "message.updated", chatContext(account, dialogId).put("messages", messages));
                            messages = new JSONArray(); eventBytes = 0;
                        }
                        messages.put(snapshot); eventBytes += bytes;
                    }

                }
                if (messages.length() == 0) return;
                value = chatContext(account, dialogId).put("messages", messages);
                manager.event(id == NotificationCenter.didReceiveNewMessages ? "message.received" : "message.updated", value);
            }
        } catch (Exception ignored) {
        }
    }

    private static int account(JSONObject arguments) throws Exception {
        int account = arguments.getInt("account_id");
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("Invalid account slot");
        UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated() || config.getClientUserId() != arguments.getLong("user_id")) {
            throw new IllegalStateException("Account session changed or logged out");
        }
        return account;
    }

    @Override
    public void request(String pluginId, String operation, JSONObject arguments, PluginBridge.Result callback) {
        try {
            if (operation.equals("accounts")) {
                JSONArray accounts = new JSONArray();
                for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                    UserConfig config = UserConfig.getInstance(i);
                    if (config.isClientActivated()) accounts.put(new JSONObject().put("account_id", i).put("user_id", config.getClientUserId())
                            .put("name", org.telegram.messenger.UserObject.getUserName(config.getCurrentUser())));
                }
                callback.complete(accounts, "");
                return;
            }
            int account = account(arguments);
            MessagesController controller = MessagesController.getInstance(account);
            if (operation.equals("presence.set")) {
                PluginPresenceController.set(pluginId, arguments.getString("_isolation_session"), account, arguments.getLong("user_id"), arguments.getBoolean("enabled"));
                callback.complete(new JSONObject().put("enabled", PluginPresenceController.enabled(account)), ""); return;
            }
            if (operation.equals("archive.record") || operation.equals("archive.query")) {
                long archiveDialog = arguments.getLong("dialog_id");
                if (archiveDialog == 0 || DialogObject.isEncryptedDialog(archiveDialog) || DialogObject.isFolderDialogId(archiveDialog)) throw new IllegalArgumentException("A cloud chat is required");
                manager.archiveRequest(pluginId, arguments.getString("_isolation_session"), operation, arguments, (result, error) -> {
                    try { account(arguments); callback.complete(result, error); } catch (Exception failure) { callback.complete(null, "Account session changed"); }
                }); return;
            }
            if (operation.equals("archive.show")) {
                long archiveDialog = arguments.getLong("dialog_id");
                if (archiveDialog == 0 || DialogObject.isEncryptedDialog(archiveDialog)) throw new IllegalArgumentException("A cloud chat is required");
                LaunchActivity activity = LaunchActivity.instance; if (activity == null || activity.isFinishing()) throw new IllegalStateException("No active window");
                activity.presentFragment(new ksnxdev.calamus.plugins.ui.PluginArchiveActivity(pluginId, arguments.getString("_isolation_session"), arguments));
                callback.complete(true, ""); return;
            }
            if (operation.equals("presence.get")) {
                long peer = arguments.getLong("dialog_id");
                if (peer <= 0) throw new IllegalArgumentException("Open a private chat with a user");
                TLRPC.User user = controller.getUser(peer); JSONObject status = new JSONObject().put("exact", false).put("kind", "unknown");
                if (user != null && user.status != null) {
                    status.put("by_me", user.status.by_me);
                    if (user.status instanceof TLRPC.TL_userStatusOffline) status.put("exact", user.status.expires > 0).put("kind", "offline").put("timestamp", user.status.expires).put("formatted", user.status.expires > 0 ? org.telegram.messenger.LocaleController.formatDateTime(user.status.expires, true) : "");
                    else if (user.status instanceof TLRPC.TL_userStatusOnline) status.put("exact", true).put("kind", "online").put("expires", user.status.expires);
                    else if (user.status instanceof TLRPC.TL_userStatusRecently) status.put("kind", "recently");
                    else if (user.status instanceof TLRPC.TL_userStatusLastWeek) status.put("kind", "last_week");
                    else if (user.status instanceof TLRPC.TL_userStatusLastMonth) status.put("kind", "last_month");
                }
                callback.complete(status, ""); return;
            }
            if (operation.equals("validate_account")) {
                callback.complete(true, "");
                return;
            }
            if (operation.equals("chats")) {
                int limit = Math.max(1, Math.min(100, arguments.optInt("limit", 50)));
                JSONArray chats = new JSONArray();
                for (TLRPC.Dialog dialog : controller.getDialogs(0)) {
                    if (DialogObject.isEncryptedDialog(dialog.id)) continue;
                    String title = "";
                    if (dialog.id > 0) {
                        TLRPC.User user = controller.getUser(dialog.id);
                        if (user != null) title = org.telegram.messenger.UserObject.getUserName(user);
                    } else {
                        TLRPC.Chat chat = controller.getChat(-dialog.id);
                        if (chat != null) title = chat.title;
                    }
                    chats.put(new JSONObject().put("dialog_id", dialog.id).put("title", title).put("unread_count", dialog.unread_count));
                    if (chats.length() >= limit) break;
                }
                callback.complete(chats, "");
                return;
            }
            if (operation.equals("show_dialog")) {
                LaunchActivity activity = LaunchActivity.instance;
                if (activity == null || activity.isFinishing()) throw new IllegalStateException("No active window");
                String title = arguments.getString("title");
                String text = arguments.getString("text");
                if (title.length() > 80 || text.length() > 4000) throw new IllegalArgumentException("Dialog is too long");
                AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
                String prefix = PluginManager.getInstance(ApplicationLoader.applicationContext).pluginName(pluginId);
                dialog.setTitle(prefix.substring(0, Math.min(40, prefix.length())) + ": " + title);
                dialog.setMessage(text);
                dialog.setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.OK), null);
                dialog.show();
                callback.complete(true, "");
                return;
            }
            long dialogId = arguments.getLong("dialog_id");
            if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId) || DialogObject.isFolderDialogId(dialogId)) throw new IllegalArgumentException("A cloud chat is required");
            if (operation.equals("open_chat")) {
                if (LaunchActivity.instance == null) throw new IllegalStateException("No active window");
                ChatActivity chat = ChatActivity.of(dialogId);
                chat.setCurrentAccount(account);
                LaunchActivity.instance.presentFragment(chat);
                callback.complete(true, "");
            } else if (operation.equals("send_message")) {
                String text = arguments.getString("text");
                if (text.trim().isEmpty() || text.length() > 4096) throw new IllegalArgumentException("Text must contain 1–4096 characters");
                if (controller.getSendPaidMessagesStars(dialogId) > 0) throw new IllegalStateException("Paid messages require sending from the chat UI");
                SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, dialogId);
                int replyTo = arguments.optInt("reply_to", 0);
                if (replyTo < 0) throw new IllegalArgumentException("Invalid reply target");
                if (replyTo > 0) {
                    TLRPC.TL_message reply = new TLRPC.TL_message();
                    reply.id = replyTo;
                    reply.dialog_id = dialogId;
                    reply.peer_id = controller.getPeer(dialogId);
                    reply.message = "";
                    params.replyToMsg = new MessageObject(account, reply, false, false);
                }
                approveSend(pluginId, account, dialogId, text, params, arguments, callback);
            } else if (operation.equals("history")) {
                TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
                request.peer = controller.getInputPeer(dialogId);
                request.limit = Math.max(1, Math.min(100, arguments.optInt("limit", 20)));
                request.offset_id = arguments.optInt("offset_id", 0);
                org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    try {
                        account(arguments);
                        if (error != null) throw new IllegalStateException(error.text);
                        TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                        controller.putUsers(result.users, false);
                        controller.putChats(result.chats, false);
                        JSONArray messages = new JSONArray();
                        for (TLRPC.Message item : result.messages) if (archivable(item)) messages.put(message(item));
                        callback.complete(messages, "");
                    } catch (Exception failure) { callback.complete(null, failure.getMessage()); }
                }));
            } else {
                throw new IllegalArgumentException("Unknown client operation");
            }
        } catch (Exception failure) { callback.complete(null, failure.getMessage()); }
    }

    private static boolean sendApprovalOpen;

    private static void approveSend(String pluginId, int account, long dialogId, String text,
            SendMessagesHelper.SendMessageParams params, JSONObject arguments, PluginBridge.Result callback) throws Exception {
        PluginManager manager = PluginManager.getInstance(ApplicationLoader.applicationContext);
        String session = arguments.optString("_isolation_session");
        if (!manager.authorizeApiNow(pluginId, session, "send_message", arguments)) throw new SecurityException("Plugin access was revoked");
        if (sendApprovalOpen) throw new IllegalStateException("Another plugin send request is awaiting confirmation");
        LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.isFinishing()) throw new IllegalStateException("No active window for send confirmation");
        sendApprovalOpen = true;
        java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.CalamusPluginSendApproval));
        builder.setMessage(manager.pluginName(pluginId) + "\n" + org.telegram.messenger.UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser())
                + " → " + dialogId + "\n\n" + text);
        builder.setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Send), (dialog, which) -> {
            if (!answered.compareAndSet(false, true)) return;
            try {
                account(arguments);
                if (!manager.authorizeApiNow(pluginId, session, "send_message", arguments)) throw new SecurityException("Plugin access was revoked");
                SendMessagesHelper.getInstance(account).sendMessage(params);
                callback.complete(new JSONObject().put("queued", true), "");
            } catch (Exception failure) { callback.complete(null, failure.getMessage()); }
        });
        builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel), (dialog, which) -> {
            if (answered.compareAndSet(false, true)) callback.complete(null, "Send was cancelled by the user");
        });
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(window -> {
            sendApprovalOpen = false;
            if (answered.compareAndSet(false, true)) callback.complete(null, "Send confirmation closed");
        });
        dialog.show();
        AndroidUtilities.runOnUIThread(() -> { if (dialog.isShowing()) dialog.dismiss(); }, 25000);
    }

    public static JSONArray accessOptions() {
        JSONArray options = new JSONArray();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (options.length() >= PluginAccessPolicy.MAX_CHAT_SCOPES) break;
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) continue;
            try {
                String name = org.telegram.messenger.UserObject.getUserName(config.getCurrentUser());
                options.put(chatContext(account, config.getClientUserId()).put("title", name + " · " + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.SavedMessages)));
                MessagesController controller = MessagesController.getInstance(account);
                for (TLRPC.Dialog item : controller.getDialogs(0)) {
                    if (options.length() >= PluginAccessPolicy.MAX_CHAT_SCOPES) break;
                    if (item.id == config.getClientUserId() || DialogObject.isEncryptedDialog(item.id) || DialogObject.isFolderDialogId(item.id)) continue;
                    String title = item.id > 0 ? org.telegram.messenger.UserObject.getUserName(controller.getUser(item.id)) : controller.getChat(-item.id) == null ? "" : controller.getChat(-item.id).title;
                    if (title.isEmpty()) continue;
                    options.put(chatContext(account, item.id).put("title", name + " · " + title));
                }
            } catch (Exception ignored) { }
        }
        return options;
    }

    public static JSONArray messageActions(int account, long dialog) {
        if (DialogObject.isEncryptedDialog(dialog)) return new JSONArray();
        return PluginManager.getInstance(ApplicationLoader.applicationContext).messageActionsForChat(chatContext(account, dialog));
    }
    public static void invokeMessageAction(JSONObject action, int account, long dialog, TLRPC.Message message) {
        if (!archivable(message)) return;
        try { PluginManager.getInstance(ApplicationLoader.applicationContext).runMessageAction(action, chatContext(account, dialog), message(message)); }
        catch (Exception ignored) { }
    }

    public static void showCommands(BaseFragment fragment, int account, long dialogId) {
        PluginManager manager = PluginManager.getInstance(ApplicationLoader.applicationContext);
        JSONArray commands = manager.commandsForChat(chatContext(account, dialogId));
        if (commands.length() == 0) {
            fragment.presentFragment(new ksnxdev.calamus.plugins.ui.PluginsActivity());
            return;
        }
        CharSequence[] labels = new CharSequence[commands.length()];
        for (int i = 0; i < labels.length; i++) {
            JSONObject command = commands.optJSONObject(i);
            labels[i] = command.optString("title") + " · ." + command.optString("name");
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        dialog.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.CalamusPlugins));
        dialog.setItems(labels, (window, index) -> manager.runCommand(commands.optJSONObject(index), "", chatContext(account, dialogId), null));
        fragment.showDialog(dialog.create());
    }
}
