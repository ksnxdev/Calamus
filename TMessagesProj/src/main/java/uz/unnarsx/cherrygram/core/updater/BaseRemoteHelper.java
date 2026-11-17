/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.core.updater;

import org.telegram.tgnet.TLRPC;

public abstract class BaseRemoteHelper {
    public void load() {
        load(null);
    }

    public void load(Delegate delegate) {
        if (delegate != null) delegate.onTLResponse(null, "CALAMUS_MANUAL_UPDATES");
    }

    public interface Delegate {
        void onTLResponse(TLRPC.TL_help_appUpdate res, String error);
    }
}
