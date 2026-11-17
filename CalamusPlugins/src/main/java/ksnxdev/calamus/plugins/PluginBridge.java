package ksnxdev.calamus.plugins;

public final class PluginBridge {
    public interface Client { void request(String pluginId, String operation, org.json.JSONObject arguments, Result callback); default void revoked(String pluginId, String session) {} default void archiveChanged(int account, long dialog) {} default void checkAccountAccess(org.json.JSONObject args) {} default boolean canShowUi() { return false; } }
    public interface Result { void complete(Object value, String error); }
    private static volatile Client client;
    public static void setClient(Client value) { client = value; }
    public static boolean canShowUi() { Client value = client; return value != null && value.canShowUi(); }
    public static void archiveChanged(int account, long dialog) {
        Client value = client;
        if (value != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> value.archiveChanged(account, dialog));
    }
    public static void revoke(String pluginId, String session) {
        Client value = client;
        if (value != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> value.revoked(pluginId, session));
    }
    public static void checkAccountAccess(org.json.JSONObject args) { Client value = client; if (value != null) value.checkAccountAccess(args); }
    public static void dispatch(String pluginId, String operation, org.json.JSONObject arguments, Result callback) {
        Client value = client;
        if (value == null) callback.complete(null, "Client API is unavailable");
        else value.request(pluginId, operation, arguments, callback);
    }
}
