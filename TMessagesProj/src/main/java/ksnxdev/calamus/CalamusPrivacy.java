package ksnxdev.calamus;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

public final class CalamusPrivacy {
    private CalamusPrivacy() {}
    public static boolean externalAdsAllowed() {
        return ApplicationLoader.applicationContext != null && ApplicationLoader.applicationContext
                .getSharedPreferences("calamus_privacy", 0).getInt("external_ads_consent_version", 0) == 1;
    }
    public static void setExternalAdsAllowed(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences("calamus_privacy", 0).edit()
                .putInt("external_ads_consent_version", enabled ? 1 : 0).apply();
    }
    public static void requestExternalAds(BaseFragment fragment, Runnable accepted) {
        if (externalAdsAllowed()) { accepted.run(); return; }
        if (fragment.getParentActivity() == null) return;
        new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.CalamusExternalAds))
                .setMessage(LocaleController.getString(R.string.CalamusExternalAdsConsent))
                .setPositiveButton(LocaleController.getString(R.string.Allow), (dialog, which) -> {
                    if (!ksnxdev.calamus.plugins.TelegramPluginHost.canShowSensitiveUi()) return;
                    setExternalAdsAllowed(true); accepted.run();
                }).setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
    }
}
