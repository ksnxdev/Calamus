import ksnxdev.calamus.plugins.PluginPresenceController;
import ksnxdev.calamus.plugins.PluginManager;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

public final class PresenceControllerTest {
    private static void check(boolean value, String text) { if (!value) throw new AssertionError(text); }
    public static void main(String[] args) {
        UserConfig.getInstance(0).user = 123;
        UserConfig.getInstance(1).user = 456;
        PluginPresenceController.set("plugin.one", "s1", 0, 123, true);
        check(PluginPresenceController.isOffline(0), "Enabled ghost should suppress online");
        check(MessagesController.getInstance(0).offline, "Enabling ghost must request offline");
        check(PluginPresenceController.enabledForPlugin("plugin.one",0), "Plugin state is missing from ghost menu");
        check(!PluginPresenceController.enabledForPlugin("plugin.other",0), "Ghost menu inherited another plugin state");
        check(!PluginPresenceController.enabled(1), "Ghost escaped selected account");
        PluginPresenceController.beginSend(0, -1);
        check(!PluginPresenceController.isOffline(0) && !MessagesController.getInstance(0).offline, "Sending must request online");
        PluginPresenceController.beginSend(0, -2);
        PluginPresenceController.endSend(0, -1);
        check(!PluginPresenceController.isOffline(0), "Concurrent send window closed early");
        PluginPresenceController.endSend(0, -2);
        check(PluginPresenceController.isOffline(0) && MessagesController.getInstance(0).offline, "Last completion must request offline");
        MessagesController.getInstance(0).offline = false;
        PluginPresenceController.endSend(0, -99);
        check(MessagesController.getInstance(0).offline, "Untracked local send acknowledgement must restore offline");
        PluginPresenceController.revoke("plugin.one", "wrong-session");
        check(PluginPresenceController.enabled(0), "Stale revocation affected current session");
        PluginPresenceController.set("plugin.two", "s2", 0, 123, true);
        PluginPresenceController.revoke("plugin.one", "s1");
        check(PluginPresenceController.enabled(0), "One plugin revoked another plugin lease");
        PluginPresenceController.revoke("plugin.two", "s2");
        check(!PluginPresenceController.enabled(0) && MessagesController.getInstance(0).resets > 0, "Revocation did not restore normal presence");
        PluginPresenceController.set("plugin.one", "s3", 0, 123, true);
        PluginPresenceController.beginSend(0, -3);
        android.os.SystemClock.now += 30001;
        check(PluginPresenceController.isOffline(0), "Failed send window did not expire");
        UserConfig.getInstance(0).user = 124;
        check(!PluginPresenceController.enabled(0), "Lease survived account identity change");
        UserConfig.getInstance(0).user = 123;
        PluginPresenceController.set("plugin.one", "s4", 0, 123, true);
        PluginManager.authorized = false;
        check(!PluginPresenceController.enabled(0), "Revoked permission still controls presence");
        PluginManager.authorized = true;
        PluginPresenceController.set("plugin.one", "s5", 1, 456, true);
        PluginPresenceController.logout(1);
        check(!PluginPresenceController.enabled(1), "Lease survived logout");
        System.out.println("Presence controller passed: offline/online send windows, concurrent sends, timeout, account/session/permission revocation and logout. No Telegram RPC was executed.");
    }
}
