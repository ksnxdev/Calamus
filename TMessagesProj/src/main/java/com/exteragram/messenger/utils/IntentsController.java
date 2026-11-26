package com.exteragram.messenger.utils;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.LaunchActivity;
import ksnxdev.calamus.extera.ui.ExteraPluginsActivity;

public final class IntentsController {
    public static final IntentsController INSTANCE = new IntentsController();
    private IntentsController() {}
    public boolean handleIntent(Intent intent) {
        return intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
            && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
            && isPluginUri(intent.getData()) && openPlugin(intent.getData());
    }
    public static boolean isPluginUri(Uri uri) {
        if (uri == null) return false;
        String name = uri.getLastPathSegment();
        try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {}
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".plugin");
    }
    public static boolean openPlugin(Uri uri) {
        var fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || uri == null) return false;
        return fragment.presentFragment(new ExteraPluginsActivity(uri));
    }
}
