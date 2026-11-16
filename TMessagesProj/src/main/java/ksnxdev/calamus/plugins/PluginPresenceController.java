package ksnxdev.calamus.plugins;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class PluginPresenceController {
    private static final class Lease {
        final String plugin, session;
        final int account;
        final long user;
        Lease(String plugin, String session, int account, long user) { this.plugin=plugin; this.session=session; this.account=account; this.user=user; }
    }
    private static final Map<Integer, Map<String, Lease>> leases = new HashMap<>();
    private static final Map<Integer, Map<Integer, Long>> sending = new HashMap<>();
    private PluginPresenceController() {}
    public static void set(String plugin, String session, int account, long user, boolean enabled) {
        boolean first = false, last = false;
        synchronized (leases) {
            Map<String, Lease> accountLeases = leases.computeIfAbsent(account, ignored -> new HashMap<>());
            Lease current = accountLeases.get(plugin);
            if (enabled) {
                if (current != null && current.session.equals(session) && current.user == user) return;
                first = accountLeases.isEmpty();
                accountLeases.put(plugin, new Lease(plugin, session, account, user));
            } else {
                if (current == null || !current.session.equals(session)) return;
                accountLeases.remove(plugin); last = accountLeases.isEmpty();
                if (last) sending.remove(account);
            }
        }
        if (first && isOffline(account)) MessagesController.getInstance(account).applyPluginPresence(true);
        else if (last) MessagesController.getInstance(account).resetPluginPresence();
    }
    public static boolean enabled(int account) {
        synchronized (leases) {
            Map<String, Lease> accountLeases = leases.get(account);
            if (accountLeases == null || ApplicationLoader.applicationContext == null) return false;
            Iterator<Lease> entries = accountLeases.values().iterator();
            while (entries.hasNext()) {
                Lease lease = entries.next();
                try {
                    JSONObject args = new JSONObject().put("account_id", account).put("user_id", lease.user);
                    if (UserConfig.getInstance(account).getClientUserId() != lease.user || !PluginManager.getInstance(ApplicationLoader.applicationContext).authorizeApiNow(lease.plugin, lease.session, "presence.set", args)) entries.remove();
                } catch (Exception error) { entries.remove(); }
            }
            return !accountLeases.isEmpty();
        }
    }
    public static boolean enabledForPlugin(String plugin, int account) {
        if (!enabled(account)) return false;
        synchronized (leases) {
            Map<String, Lease> accountLeases = leases.get(account);
            return accountLeases != null && accountLeases.containsKey(plugin);
        }
    }
    public static boolean isOffline(int account) {
        if (!enabled(account)) return false;
        synchronized (leases) {
            Map<Integer, Long> pending = sending.get(account);
            if (pending == null) return true;
            long now = android.os.SystemClock.elapsedRealtime();
            pending.values().removeIf(expires -> expires < now);
            return pending.isEmpty();
        }
    }
    public static void beginSend(int account, int message) {
        if (!enabled(account)) return;
        boolean first;
        synchronized (leases) {
            Map<Integer, Long> pending = sending.computeIfAbsent(account, ignored -> new HashMap<>());
            long now = android.os.SystemClock.elapsedRealtime(); pending.values().removeIf(expires -> expires < now);
            first = pending.isEmpty(); pending.put(message, now+30000);
        }
        if (first) MessagesController.getInstance(account).applyPluginPresence(false);
    }
    public static void endSend(int account, int message) {
        synchronized (leases) {
            Map<Integer, Long> pending = sending.get(account);
            if (pending != null) pending.remove(message);
        }
        if (isOffline(account)) MessagesController.getInstance(account).applyPluginPresence(true);
    }
    public static void revoke(String plugin, String session) {
        java.util.ArrayList<Integer> changed = new java.util.ArrayList<>();
        synchronized (leases) {
            for (Map.Entry<Integer, Map<String, Lease>> entry : leases.entrySet()) {
                Lease lease=entry.getValue().get(plugin);
                if (lease != null && lease.session.equals(session)) { entry.getValue().remove(plugin); changed.add(entry.getKey()); }
            }
        }
        for (int account : changed) if (!enabled(account)) { synchronized (leases) { sending.remove(account); } MessagesController.getInstance(account).resetPluginPresence(); }
    }
    public static void logout(int account) { synchronized (leases) { leases.remove(account); sending.remove(account); } }
}
