package uz.ksnxdev.calamus;

import android.os.Build;
import android.util.SparseIntArray;
import android.util.SparseBooleanArray;
import androidx.core.graphics.ColorUtils;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;

public final class CalamusMaterial {
    private static final SparseBooleanArray paletteKeys = new SparseBooleanArray();

    private CalamusMaterial() {}

    public static boolean ownsColor(int key) {
        return paletteKeys.get(key);
    }

    public static boolean isEnabled() {
        return ApplicationLoader.applicationContext != null && CalamusConfig.INSTANCE.getMaterial3();
    }

    public static int seedColor() {
        if (CalamusConfig.INSTANCE.getUseSystemColor() && Build.VERSION.SDK_INT >= 31) {
            return ApplicationLoader.applicationContext.getColor(android.R.color.system_accent1_400);
        }
        return CalamusConfig.INSTANCE.getUseSystemColor() ? 0xff677e6d : CalamusConfig.INSTANCE.getAppColor();
    }

    public static void apply(SparseIntArray colors, boolean dark) {
        if (!CalamusConfig.INSTANCE.getMaterial3()) return;
        int seed = seedColor();
        paletteKeys.clear();
        int primary = tonal(seed, 28, dark ? 80 : 40);
        int background = tonal(seed, 5, dark ? 6 : 98);
        int surface = tonal(seed, 5, dark ? 10 : 100);
        int container = tonal(seed, 7, dark ? 16 : 94);
        int primaryContainer = tonal(seed, 18, dark ? 30 : 90);
        int containerPressed = ColorUtils.blendARGB(primaryContainer, primary, 0.10f);
        int onSurface = tonal(seed, 5, dark ? 90 : 10);
        int secondary = tonal(seed, 8, dark ? 80 : 30);
        int onPrimary = tonal(seed, 24, dark ? 20 : 100);
        int onContainer = tonal(seed, 18, dark ? 90 : 10);
        int outline = tonal(seed, 7, dark ? 60 : 50);
        put(colors, surface,
                Theme.key_windowBackgroundWhite, Theme.key_actionBarDefault, Theme.key_actionBarDefaultArchived, Theme.key_chat_inBubble, Theme.key_chat_inBubbleShadow);
        put(colors, background,
                Theme.key_windowBackgroundGray, Theme.key_windowBackgroundGrayShadow);
        put(colors, container,
                Theme.key_dialogBackground, Theme.key_actionBarDefaultSubmenuBackground, Theme.key_chat_messagePanelBackground, Theme.key_chat_messagePanelShadow, Theme.key_chat_inBubbleSelected);
        put(colors, primaryContainer,
                Theme.key_chats_actionBackground, Theme.key_chat_outBubble, Theme.key_chat_outBubbleGradient1, Theme.key_chat_outBubbleGradient2, Theme.key_chat_outBubbleGradient3, Theme.key_chat_outBubbleShadow);
        put(colors, containerPressed,
                Theme.key_chats_actionPressedBackground, Theme.key_chat_outBubbleSelected);
        put(colors, ColorUtils.setAlphaComponent(primary, dark ? 28 : 22),
                Theme.key_chat_inBubbleSelectedOverlay, Theme.key_chat_outBubbleSelectedOverlay, Theme.key_chat_outBubbleGradientSelectedOverlay);
        put(colors, onSurface,
                Theme.key_windowBackgroundWhiteBlackText, Theme.key_dialogTextBlack, Theme.key_actionBarDefaultTitle, Theme.key_actionBarDefaultSearch, Theme.key_actionBarDefaultSubmenuItem, Theme.key_chats_name);
        put(colors, dark ? 0xffffffff : onSurface,
                Theme.key_chat_messageTextIn, Theme.key_chat_messageTextOut, Theme.key_chat_messagePanelText);
        put(colors, secondary, Theme.key_chats_unreadCounterMuted,
                Theme.key_windowBackgroundWhiteGrayText, Theme.key_windowBackgroundWhiteGrayText2, Theme.key_windowBackgroundWhiteGrayText3, Theme.key_dialogTextGray, Theme.key_dialogTextGray2, Theme.key_actionBarDefaultSubtitle, Theme.key_actionBarDefaultIcon, Theme.key_actionBarDefaultSearchPlaceholder, Theme.key_actionBarDefaultSubmenuItemIcon, Theme.key_chats_message, Theme.key_chats_date, Theme.key_chat_inTimeText, Theme.key_chat_outTimeText, Theme.key_chat_messagePanelHint, Theme.key_chat_messagePanelIcons, Theme.key_glass_tabUnselected);
        put(colors, primary, Theme.key_switchTrackChecked, Theme.key_switchTrackBlueChecked,
                Theme.key_windowBackgroundWhiteValueText, Theme.key_dialogTextBlue2, Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhiteBlueText2, Theme.key_windowBackgroundWhiteBlueText3, Theme.key_windowBackgroundWhiteBlueHeader, Theme.key_windowBackgroundWhiteLinkText, Theme.key_dialogTextBlue, Theme.key_dialogTextLink, Theme.key_actionBarTabActiveText, Theme.key_actionBarTabLine, Theme.key_featuredStickers_addButton, Theme.key_featuredStickers_addButtonPressed, Theme.key_chats_unreadCounter, Theme.key_glass_tabSelected, Theme.key_glass_tabSelectedText, Theme.key_chat_messageLinkIn, Theme.key_chat_messageLinkOut, Theme.key_chat_inReplyNameText, Theme.key_chat_outReplyNameText, Theme.key_chat_outSentCheck, Theme.key_chat_outSentCheckSelected, Theme.key_chat_outSentCheckRead, Theme.key_chat_messagePanelSend, Theme.key_cgGradient1, Theme.key_cgGradient2);
        put(colors, onPrimary, Theme.key_switchTrackBlueThumbChecked,
                Theme.key_featuredStickers_buttonText, Theme.key_chats_unreadCounterText);
        put(colors, onContainer,
                Theme.key_chats_actionIcon);
        put(colors, outline,
                Theme.key_switchTrack);
        put(colors, tonal(seed, 7, dark ? 30 : 90), Theme.key_divider);
        put(colors, container, Theme.key_switch2Track, Theme.key_switchTrackBlue,
                Theme.key_groupcreate_spanBackground, Theme.key_dialogSearchBackground);
        put(colors, primary, Theme.key_switch2TrackChecked, Theme.key_checkbox, Theme.key_radioBackgroundChecked,
                Theme.key_dialogRadioBackgroundChecked, Theme.key_dialogCheckboxSquareBackground,
                Theme.key_dialogInputFieldActivated, Theme.key_windowBackgroundWhiteInputFieldActivated,
                Theme.key_progressCircle, Theme.key_windowBackgroundWhiteBlueButton);
        put(colors, onPrimary, Theme.key_checkboxCheck, Theme.key_dialogCheckboxSquareCheck);
        put(colors, outline, Theme.key_radioBackground, Theme.key_dialogRadioBackground,
                Theme.key_windowBackgroundWhiteInputField, Theme.key_dialogInputField);
        put(colors, onSurface, Theme.key_dialogSearchText, Theme.key_dialogSearchIcon);
        put(colors, secondary, Theme.key_dialogSearchHint);
        put(colors, onSurface, Theme.key_actionBarActionModeDefaultIcon);
        put(colors, primaryContainer, Theme.key_actionBarActionModeDefault);
        int error = dark ? 0xffffb4ab : 0xffba1a1a;
        put(colors, error, Theme.key_text_RedRegular);
        put(colors, onSurface, Theme.key_groupcreate_spanText, Theme.key_chat_botSwitchToInlineText);
        put(colors, container, Theme.key_graySection);
        put(colors, secondary, Theme.key_graySectionText);
        put(colors, tonal(seed, 28, 40), Theme.key_chat_messagePanelVoiceBackground);
        put(colors, 0xffffffff, Theme.key_chat_messagePanelVoicePressed);
        put(colors, onContainer, Theme.key_chat_messagePanelVoiceDuration);
        int fileSecondary = ColorUtils.blendARGB(secondary, onSurface, 0.65f);
        put(colors, onSurface, Theme.key_chat_inReplyMessageText, Theme.key_chat_outReplyMessageText);
        put(colors, fileSecondary, Theme.key_chat_inReplyMediaMessageText, Theme.key_chat_outReplyMediaMessageText);
        put(colors, dark ? 0xffe6e6e6 : onSurface, Theme.key_chat_inReplyMediaMessageSelectedText, Theme.key_chat_outReplyMediaMessageSelectedText);
        put(colors, primary, Theme.key_chat_inReplyLine, Theme.key_chat_outReplyLine, Theme.key_chat_outReplyLine2, Theme.key_chat_inQuote, Theme.key_chat_outQuote);
        put(colors, onSurface, Theme.key_chat_inFileNameText, Theme.key_chat_outFileNameText, Theme.key_chat_inAudioTitleText, Theme.key_chat_outAudioTitleText);
        put(colors, fileSecondary, Theme.key_chat_inAudioDurationText, Theme.key_chat_outAudioDurationText, Theme.key_chat_inAudioDurationSelectedText, Theme.key_chat_outAudioDurationSelectedText);
        put(colors, fileSecondary, Theme.key_chat_inFileInfoText, Theme.key_chat_outFileInfoText, Theme.key_chat_inFileInfoSelectedText, Theme.key_chat_outFileInfoSelectedText);
        put(colors, container, Theme.key_chat_inFileBackground, Theme.key_chat_outFileBackground, Theme.key_chat_inLoader, Theme.key_chat_outLoader);
        put(colors, containerPressed, Theme.key_chat_inFileBackgroundSelected, Theme.key_chat_outFileBackgroundSelected, Theme.key_chat_inLoaderSelected, Theme.key_chat_outLoaderSelected);
        put(colors, onContainer, Theme.key_chat_inMediaIcon, Theme.key_chat_outMediaIcon, Theme.key_chat_inMediaIconSelected, Theme.key_chat_outMediaIconSelected);
        put(colors, primary, Theme.key_chat_inFileProgress, Theme.key_chat_outFileProgress, Theme.key_chat_inFileProgressSelected, Theme.key_chat_outFileProgressSelected);
        put(colors, onContainer, Theme.key_chat_inForwardedNameText, Theme.key_chat_outForwardedNameText,
                Theme.key_chat_inViaBotNameText, Theme.key_chat_outViaBotNameText, Theme.key_chat_inPsaNameText, Theme.key_chat_outPsaNameText,
                Theme.key_chat_inSiteNameText, Theme.key_chat_outSiteNameText, Theme.key_chat_inPreviewInstantText, Theme.key_chat_outPreviewInstantText,
                Theme.key_chat_messageLinkIn, Theme.key_chat_messageLinkOut, Theme.key_chat_inReplyNameText, Theme.key_chat_outReplyNameText);
        put(colors, onSurface, Theme.key_chat_inContactNameText, Theme.key_chat_outContactNameText);
        put(colors, fileSecondary, Theme.key_chat_inContactPhoneText, Theme.key_chat_outContactPhoneText,
                Theme.key_chat_inContactPhoneSelectedText, Theme.key_chat_outContactPhoneSelectedText,
                Theme.key_chat_inAudioPerformerText, Theme.key_chat_outAudioPerformerText,
                Theme.key_chat_inAudioPerformerSelectedText, Theme.key_chat_outAudioPerformerSelectedText,
                Theme.key_chat_inVenueInfoText, Theme.key_chat_outVenueInfoText, Theme.key_chat_inVenueInfoSelectedText, Theme.key_chat_outVenueInfoSelectedText);
        put(colors, onContainer, Theme.key_chat_inInstant, Theme.key_chat_outInstant, Theme.key_chat_inInstantSelected, Theme.key_chat_outInstantSelected,
                Theme.key_chat_inPreviewLine, Theme.key_chat_outPreviewLine);
        put(colors, primary, Theme.key_chat_inAudioProgress, Theme.key_chat_outAudioProgress, Theme.key_chat_inAudioSelectedProgress, Theme.key_chat_outAudioSelectedProgress,
                Theme.key_chat_inVoiceSeekbarFill, Theme.key_chat_outVoiceSeekbarFill, Theme.key_chat_inAudioSeekbarFill, Theme.key_chat_outAudioSeekbarFill);
        put(colors, ColorUtils.blendARGB(fileSecondary, primaryContainer, 0.25f),
                Theme.key_chat_inVoiceSeekbar, Theme.key_chat_outVoiceSeekbar, Theme.key_chat_inVoiceSeekbarSelected, Theme.key_chat_outVoiceSeekbarSelected,
                Theme.key_chat_inAudioSeekbar, Theme.key_chat_outAudioSeekbar, Theme.key_chat_inAudioSeekbarSelected, Theme.key_chat_outAudioSeekbarSelected);
        put(colors, fileSecondary, Theme.key_chat_inViews, Theme.key_chat_outViews, Theme.key_chat_inViewsSelected, Theme.key_chat_outViewsSelected,
                Theme.key_chat_inMenu, Theme.key_chat_outMenu, Theme.key_chat_inMenuSelected, Theme.key_chat_outMenuSelected);
        put(colors, onContainer, Theme.key_chat_inSentClock, Theme.key_chat_outSentClock, Theme.key_chat_inSentClockSelected, Theme.key_chat_outSentClockSelected,
                Theme.key_chat_outSentCheck, Theme.key_chat_outSentCheckSelected, Theme.key_chat_outSentCheckRead, Theme.key_chat_outSentCheckReadSelected);
        put(colors, fileSecondary, Theme.key_chat_inTimeText, Theme.key_chat_outTimeText, Theme.key_chat_inTimeSelectedText, Theme.key_chat_outTimeSelectedText);
        colors.put(Theme.key_listSelector, ColorUtils.setAlphaComponent(primary, dark ? 22 : 18));
    }

    public static int filledActionColor(Theme.ResourcesProvider provider) {
        return Theme.getColor(isEnabled() ? Theme.key_chat_messagePanelVoiceBackground : Theme.key_chat_messagePanelSend, provider);
    }

    public static int panelColor(int color) {
        return ColorUtils.setAlphaComponent(color, CalamusConfig.INSTANCE.getTranslucentElements() ? 150 : 255);
    }

    public static boolean panelBlurEnabled() {
        return isEnabled() && CalamusConfig.INSTANCE.getTranslucentElements();
    }

    public static android.graphics.drawable.Drawable createNavigationBackground(Theme.ResourcesProvider provider) {
        return createNavigationBackground(provider, null);
    }

    public static android.graphics.drawable.Drawable createNavigationBackground(Theme.ResourcesProvider provider,
            android.graphics.drawable.Drawable backdrop) {
        int surface = Theme.getColor(Theme.key_dialogBackground, provider);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(backdrop == null ? panelColor(surface) : android.graphics.Color.TRANSPARENT);
        background.setCornerRadius(org.telegram.messenger.AndroidUtilities.dp(24));
        android.graphics.drawable.Drawable content = backdrop == null ? background :
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{backdrop, background});
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                Theme.getColor(Theme.key_listSelector, provider)), content, null);
    }

    public static int tonal(int seed, double chroma, double lightness) {
        double[] lab = new double[3];
        ColorUtils.colorToLAB(seed, lab);
        double original = Math.hypot(lab[1], lab[2]);
        double scale = original == 0 ? 0 : Math.min(chroma, original) / original;
        return ColorUtils.LABToColor(lightness, lab[1] * scale, lab[2] * scale);
    }

    public static android.graphics.drawable.Drawable surfaceDrawable(int color, float radius, boolean topOnly, int inset) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        float r = org.telegram.messenger.AndroidUtilities.dp(radius);
        shape.setCornerRadii(new float[]{r, r, r, r, topOnly ? 0 : r, topOnly ? 0 : r, topOnly ? 0 : r, topOnly ? 0 : r});
        return new android.graphics.drawable.InsetDrawable(shape, org.telegram.messenger.AndroidUtilities.dp(inset));
    }

    public static int switchWidth() {
        return isEnabled() ? 52 : 39;
    }

    public static int settingsIconBackground() {
        return ColorUtils.blendARGB(Theme.getColor(Theme.key_dialogBackground),
                Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.14f);
    }

    public static int settingsIconForeground() {
        return ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader),
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.25f);
    }

    private static void put(SparseIntArray colors, int value, int... keys) {
        for (int key : keys) {
            colors.put(key, value);
            paletteKeys.put(key, true);
        }
    }
}
