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
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(seed, hsl);
        float saturation = Math.min(hsl[1], 0.22f);
        int primary = tone(hsl[0], saturation, dark ? 0.70f : 0.37f);
        int background = tone(hsl[0], saturation * 0.45f, dark ? 0.105f : 0.96f);
        int surface = tone(hsl[0], saturation * 0.45f, dark ? 0.13f : 0.98f);
        int container = tone(hsl[0], saturation * 0.45f, dark ? 0.17f : 0.935f);
        int primaryContainer = ColorUtils.blendARGB(container, primary, dark ? 0.13f : 0.12f);
        int containerPressed = ColorUtils.blendARGB(primaryContainer, primary, 0.10f);
        int onSurface = tone(hsl[0], saturation * 0.25f, dark ? 0.80f : 0.24f);
        int secondary = tone(hsl[0], saturation * 0.25f, dark ? 0.66f : 0.40f);
        int onPrimary = tone(hsl[0], saturation * 0.45f, dark ? 0.16f : 0.98f);
        int onContainer = ColorUtils.blendARGB(primary, onSurface, 0.25f);
        int outline = ColorUtils.blendARGB(container, secondary, 0.24f);
        put(colors, surface,
                Theme.key_windowBackgroundWhite, Theme.key_actionBarDefault, Theme.key_actionBarDefaultArchived, Theme.key_chat_inBubble, Theme.key_chat_inBubbleShadow);
        put(colors, background,
                Theme.key_windowBackgroundGray, Theme.key_windowBackgroundGrayShadow, Theme.key_chat_wallpaper, Theme.key_chat_wallpaper_gradient_to1, Theme.key_chat_wallpaper_gradient_to2);
        put(colors, container,
                Theme.key_dialogBackground, Theme.key_actionBarDefaultSubmenuBackground, Theme.key_chat_messagePanelBackground, Theme.key_chat_messagePanelShadow, Theme.key_chat_inBubbleSelected);
        put(colors, primaryContainer,
                Theme.key_chats_actionBackground, Theme.key_chat_outBubble, Theme.key_chat_outBubbleGradient1, Theme.key_chat_outBubbleGradient2, Theme.key_chat_outBubbleGradient3, Theme.key_chat_outBubbleShadow);
        put(colors, containerPressed,
                Theme.key_chats_actionPressedBackground, Theme.key_chat_outBubbleSelected);
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
                Theme.key_divider, Theme.key_switchTrack);
        int fileSecondary = ColorUtils.blendARGB(secondary, onSurface, 0.65f);
        put(colors, onSurface, Theme.key_chat_inFileNameText, Theme.key_chat_outFileNameText, Theme.key_chat_inAudioTitleText, Theme.key_chat_outAudioTitleText);
        put(colors, fileSecondary, Theme.key_chat_inFileInfoText, Theme.key_chat_outFileInfoText, Theme.key_chat_inFileInfoSelectedText, Theme.key_chat_outFileInfoSelectedText);
        put(colors, container, Theme.key_chat_inFileBackground, Theme.key_chat_outFileBackground, Theme.key_chat_inLoader, Theme.key_chat_outLoader);
        put(colors, containerPressed, Theme.key_chat_inFileBackgroundSelected, Theme.key_chat_outFileBackgroundSelected, Theme.key_chat_inLoaderSelected, Theme.key_chat_outLoaderSelected);
        put(colors, onContainer, Theme.key_chat_inMediaIcon, Theme.key_chat_outMediaIcon, Theme.key_chat_inMediaIconSelected, Theme.key_chat_outMediaIconSelected);
        put(colors, primary, Theme.key_chat_inFileProgress, Theme.key_chat_outFileProgress, Theme.key_chat_inFileProgressSelected, Theme.key_chat_outFileProgressSelected);
        put(colors, secondary, Theme.key_chat_inTimeSelectedText, Theme.key_chat_outTimeSelectedText);
        colors.put(Theme.key_listSelector, ColorUtils.setAlphaComponent(primary, dark ? 22 : 18));
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
        background.setCornerRadius(org.telegram.messenger.AndroidUtilities.dp(28));
        background.setStroke(org.telegram.messenger.AndroidUtilities.dp(1), ColorUtils.blendARGB(
                surface, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, provider), 0.50f));
        android.graphics.drawable.Drawable content = backdrop == null ? background :
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{backdrop, background});
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                Theme.getColor(Theme.key_listSelector, provider)), content, null);
    }

    private static int tone(float hue, float saturation, float lightness) {
        return ColorUtils.HSLToColor(new float[]{hue, saturation, lightness});
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
