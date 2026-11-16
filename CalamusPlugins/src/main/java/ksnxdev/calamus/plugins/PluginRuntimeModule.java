package ksnxdev.calamus.plugins;

import android.content.Context;
import android.os.Build;

public final class PluginRuntimeModule {
    public static boolean ready(Context context) { return Build.VERSION.SDK_INT >= 29; }
    static boolean isIsolatedUid(int uid) { int app = uid % 100000; return app >= 90000 && app <= 99999; }
}
