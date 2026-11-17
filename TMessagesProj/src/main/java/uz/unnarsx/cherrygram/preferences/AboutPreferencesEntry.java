/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;

import uz.unnarsx.cherrygram.core.configs.CherrygramCoreConfig;
import uz.unnarsx.cherrygram.core.firebase.FirebaseAnalyticsHelper;
import uz.unnarsx.cherrygram.core.helpers.CGResourcesHelper;
import uz.unnarsx.cherrygram.core.helpers.DeeplinkHelper;
import uz.unnarsx.cherrygram.misc.Constants;
import uz.unnarsx.cherrygram.preferences.helpers.SettingsHelper;

public class AboutPreferencesEntry extends BaseCGPreferencesEntry {

    private final int readmeRow = 1;
    private final int updatesRow = 2;
    private final int debugPrefsRow = 3;

    private final int channelRow = 4;
    private final int githubRow = 9;
    private final int crowdinRow = 10;
    private final int policyRow = 11;


    @Override
    protected CharSequence getTitle() {
        FirebaseAnalyticsHelper.INSTANCE.trackEventWithEmptyBundle("about_preferences_screen");
        return getString(R.string.CGP_Header_About);
    }

    @Override
    protected String getKey() {
        return DeeplinkHelper.DeepLinksRepo.CG_About;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.Info)));
        items.add(
                SettingsHelper.asTextDetail(
                        readmeRow,
                        0,
                        "Calamus " + BuildConfig.CALAMUS_VERSION_NAME + " | Cherrygram " + CGResourcesHelper.getCherryVersion(),
                        getString(R.string.CGP_About_Desc)
                )
        );
        items.add(
                SettingsActivity.SettingCell.Factory.of(
                        updatesRow,
                        IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                        R.drawable.settings_refresh_filled_solar,
                        getString(R.string.UP_Category_Updates),
                        "@CalamusGram"
                )
                .slug(DeeplinkHelper.DeepLinksRepo.CG_Updater_Bottom_Sheet)
        );
        items.add(
                SettingsActivity.SettingCell.Factory.of(
                        debugPrefsRow,
                        IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                        R.drawable.settings_tube_filled_solar,
                        "Debug // W.I.P",
                        getString(R.string.CGP_Experimental_Desc),
                        true
                )
                .slug(DeeplinkHelper.DeepLinksRepo.CG_Debug)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.CGP_Links)));
        items.add(SettingsHelper.asTextDetail(channelRow, R.drawable.msg_channel_solar, getString(R.string.CalamusChannel), "@CalamusGram"));
        items.add(UItem.asShadow(null));

        if (!CherrygramCoreConfig.isStandalonePremiumBuild()) {
            String value;
            if (CherrygramCoreConfig.isStandaloneBetaBuild() || CherrygramCoreConfig.isDevBuild()) {
                value = "GitHub";
            } else {
                value = "commit " + BuildConfig.GIT_COMMIT_HASH.substring(0, 8);
            }
            items.add(UItem.asButton(githubRow, R.drawable.github_cat, getString(R.string.CGP_Source), value)
                    .slug("source_code")
            );
        }

        items.add(UItem.asButton(crowdinRow, R.drawable.msg_translate_solar, getString(R.string.CGP_Crowdin), "Crowdin")
                .slug("crowdin")
        );
        items.add(UItem.asButton(policyRow, R.drawable.msg_policy_solar, getString(R.string.PrivacyPolicy))
                .slug("policy")
        );
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == readmeRow) {
            Browser.openUrl(getContext(), Constants.CG_GITHUB_URL + "#readme");
        } else if (item.id == updatesRow) {
            getMessagesController().openByUserName("CalamusGram", this, 1);
        } else if (item.id == debugPrefsRow) {
            CherrygramPreferencesNavigator.INSTANCE.createDebug(this);
        } else if (item.id == channelRow) {
            getMessagesController().openByUserName("CalamusGram", this, 1);
        } else if (item.id == githubRow) {
            Browser.openUrl(getContext(), Constants.CG_GITHUB_URL);
        } else if (item.id == crowdinRow) {
            Browser.openUrl(getContext(), Constants.CG_CROWDIN_URL);
        } else if (item.id == policyRow) {
            presentFragment(new ksnxdev.calamus.CalamusPrivacyActivity());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id == channelRow) {
            AndroidUtilities.addToClipboard("@CalamusGram");
            return true;
        } else if (item.id == crowdinRow) {
            AndroidUtilities.addToClipboard(Constants.CG_CROWDIN_URL);
            return true;
        } else if (item.id == policyRow) {
            AndroidUtilities.addToClipboard(Constants.CALAMUS_PRIVACY_URL);
            return true;
        }
        return false;
    }

    public static String getLastCheckUpdateTime() {
        return getString(R.string.UP_LastCheck) + ": " + LocaleController.formatDateTime(SharedConfig.lastUpdateCheckTime / 1000, true);
    }

}
