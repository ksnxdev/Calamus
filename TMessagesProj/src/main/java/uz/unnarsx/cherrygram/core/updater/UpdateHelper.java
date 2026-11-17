/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.core.updater;

import org.telegram.messenger.Utilities;

public class UpdateHelper extends BaseRemoteHelper {
    public static final long updateCheckInterval = 3600000L; // 1 hour

    public static UpdateHelper getInstance() {
        return InstanceHolder.instance;
    }

    public static boolean isNew(String currentVersion, String newVersion) {
        String[] current = currentVersion.split("\\.");
        String[] latest = newVersion.split("\\.");
        int cmp = compareVersions(current, latest);
        return cmp < 0;
    }

    private static int compareVersions(String[] a, String[] b) {
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int v1 = i < a.length ? Utilities.parseInt(a[i]) : 0;
            int v2 = i < b.length ? Utilities.parseInt(b[i]) : 0;
            if (v1 != v2) {
                return Integer.compare(v1, v2);
            }
        }
        return 0;
    }

    public void checkNewVersionAvailable(Delegate delegate) {
        load(delegate);
    }

    public void checkNewVersionAvailable(Delegate delegate, boolean updateAlways) {
        load(delegate);
    }

    private static final class InstanceHolder {
        private static final UpdateHelper instance = new UpdateHelper();
    }
}
