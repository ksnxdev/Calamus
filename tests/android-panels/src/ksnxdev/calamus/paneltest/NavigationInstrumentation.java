package ksnxdev.calamus.paneltest;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;

public class NavigationInstrumentation extends SettingsInstrumentation {
    private SharedPreferences navigationPrefs;
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private int color(String name) throws Exception { return (int) theme.getMethod("getColor", int.class).invoke(null, theme.getField("key_" + name).getInt(null)); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext(); loader = context.getClassLoader();
            main(() -> { try { type("org.telegram.messenger.ApplicationLoader").getMethod("postInitApplication").invoke(null); } catch (Exception e) { throw new RuntimeException(e); } });
            context.startActivity(new Intent().setClassName("ksnxdev.calamus", "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for (int i = 0; i < 100 && activity == null; i++) { Thread.sleep(100); activity = (android.app.Activity) type("org.telegram.ui.LaunchActivity").getField("instance").get(null); }
            check(activity != null, "Launch activity missing");
            theme = type("org.telegram.ui.ActionBar.Theme");
            navigationPrefs = context.getSharedPreferences("mainconfig", 0);
            boolean existed = navigationPrefs.contains("AP_ShowMainTabsTitle"), saved = navigationPrefs.getBoolean("AP_ShowMainTabsTitle", true);
            Object oldTheme = theme.getMethod("getCurrentTheme").invoke(null);
            try {
                for (boolean dark : new boolean[]{false, true}) {
                    navigationPrefs.edit().putBoolean("AP_ShowMainTabsTitle", true).commit();
                    main(() -> { try { Object info = theme.getMethod("getTheme", String.class).invoke(null, dark ? "Monet Dark" : "Monet Light"); theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, info, false, dark); } catch (Exception e) { throw new RuntimeException(e); } });
                    verify(dark);
                }
            } finally {
                SharedPreferences.Editor edit = navigationPrefs.edit();
                if (existed) edit.putBoolean("AP_ShowMainTabsTitle", saved); else edit.remove("AP_ShowMainTabsTitle"); edit.commit();
                main(() -> { try { theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, oldTheme, false, (boolean) oldTheme.getClass().getMethod("isDark").invoke(oldTheme)); } catch (Exception e) { throw new RuntimeException(e); } });
            }
            result.putString("stream", "Navigation checks passed: light/dark, common label baseline, lowered labels, icon centering, accessible names, switch persistence and centered plugin header\n"); finish(-1, result);
        } catch (Throwable failure) { result.putString("stream", android.util.Log.getStackTraceString(failure)); finish(0, result); }
    }

    private void verify(boolean dark) throws Exception {
        String mode = dark ? "dark" : "light";
        Object appearance = open("uz.unnarsx.cherrygram.preferences.AppearancePreferencesEntry");
        scrollToSwitch(appearance); save("appearance-labels-" + mode + "-on.png");
        renderNavigation(mode, true);
        toggleLabels(appearance);
        check(!navigationPrefs.getBoolean("AP_ShowMainTabsTitle", true), "Labels switch did not persist off");
        appearance = open("uz.unnarsx.cherrygram.preferences.AppearancePreferencesEntry");
        scrollToSwitch(appearance); save("appearance-labels-" + mode + "-off.png");
        renderNavigation(mode, false);
        toggleLabels(appearance);
        check(navigationPrefs.getBoolean("AP_ShowMainTabsTitle", false), "Labels switch did not persist on");
        Object plugins = open("ksnxdev.calamus.plugins.ui.PluginsActivity");
        main(() -> { try {
            View root = (View) type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getFragmentView").invoke(plugins);
            TextView title = findText(root, "Installed plugins"); check(title != null, "Installed plugins header missing");
            View parent = (View) title.getParent();
            check(Math.abs(title.getTop() + title.getHeight() / 2f - parent.getHeight() / 2f) <= 1, "Plugin header text box is not centered");
            float glyphCenter = title.getTop() + title.getBaseline() + (title.getPaint().ascent() + title.getPaint().descent()) / 2f;
            check(Math.abs(glyphCenter - parent.getHeight() / 2f) <= dp(2), "Plugin header glyphs are not vertically centered");
        } catch (Exception e) { throw new RuntimeException(e); } });
        save("plugins-header-" + mode + ".png");
    }

    private Rect scrollToSwitch(Object appearance) throws Exception {
        Rect bounds = new Rect();
        Field field = type("uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry").getDeclaredField("listView"); field.setAccessible(true);
        Object list = field.get(appearance);
        int position = (int) list.getClass().getMethod("findPositionByItemSlug", String.class).invoke(list, "navigationLabels");
        check(position >= 0, "Navigation label switch is missing");
        main(() -> { try { Object manager = list.getClass().getMethod("getLayoutManager").invoke(list); manager.getClass().getMethod("scrollToPositionWithOffset", int.class, int.class).invoke(manager, position, dp(60)); } catch (Exception e) { throw new RuntimeException(e); } });
        Thread.sleep(400);
        main(() -> { try { Object manager = list.getClass().getMethod("getLayoutManager").invoke(list); View row = (View) manager.getClass().getMethod("findViewByPosition", int.class).invoke(manager, position); check(row != null && row.getGlobalVisibleRect(bounds), "Switch is not visible"); } catch (Exception e) { throw new RuntimeException(e); } });
        return bounds;
    }
    private void toggleLabels(Object appearance) throws Exception { injectTap(scrollToSwitch(appearance)); }

    private void renderNavigation(String mode, boolean labels) throws Exception {
        Dialog[] dialog = new Dialog[1]; View[] tabs = new View[3];
        main(() -> { try {
            Class<?> provider = type("org.telegram.ui.ActionBar.Theme$ResourcesProvider"), tabType = type("org.telegram.ui.Components.glass.GlassTabView");
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(color("windowBackgroundGray"));
            LinearLayout bar = new LinearLayout(activity); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(2), dp(2), dp(2), dp(2));
            bar.setBackground((android.graphics.drawable.Drawable) type("uz.ksnxdev.calamus.CalamusMaterial").getMethod("createNavigationBackground", provider).invoke(null, new Object[]{null}));
            ViewGroup group = (ViewGroup) type("org.telegram.ui.MainTabsLayout").getConstructor(android.content.Context.class, provider).newInstance(activity, null);
            group.setPadding(0, dp(18), 0, dp(labels ? 12 : 18));
            tabs[0] = (View) tabType.getMethod("createAvatar", android.content.Context.class, provider, int.class, int.class).invoke(null, activity, null, 0, string("Settings"));
            Class<?> animation = type("org.telegram.ui.Components.glass.GlassTabView$TabAnimation");
            tabs[1] = (View) tabType.getMethod("createMainTab", android.content.Context.class, provider, animation, int.class).invoke(null, activity, null, animation.getField("CHATS").get(null), string("MainTabsChats"));
            for (int i = 0; i < 2; i++) { group.addView(tabs[i]); group.getClass().getMethod("setViewVisible", View.class, boolean.class, boolean.class).invoke(group, tabs[i], true, false); }
            tabType.getMethod("setSelected", boolean.class, boolean.class).invoke(tabs[1], true, false);
            bar.addView(group, new LinearLayout.LayoutParams(0, dp(80), 2));
            int icon = context.getResources().getIdentifier("outline_header_search", "drawable", "ksnxdev.calamus");
            tabs[2] = (View) tabType.getMethod("createStaticTab", android.content.Context.class, provider, int.class, int.class, boolean.class).invoke(null, activity, null, icon, string("Search"), labels);
            tabs[2].setPadding(0, dp(18), 0, dp(labels ? 12 : 18)); bar.addView(tabs[2], new LinearLayout.LayoutParams(0, dp(80), 1));
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, dp(84), Gravity.BOTTOM); position.setMargins(dp(12), 0, dp(12), dp(24)); root.addView(bar, position);
            dialog[0] = new Dialog(activity); dialog[0].setContentView(root); dialog[0].show(); dialog[0].getWindow().setLayout(-1, -1);
        } catch (Exception e) { throw new RuntimeException(e); } });
        try {
            Thread.sleep(600);
            main(() -> { try {
                Field textField = type("org.telegram.ui.Components.glass.GlassTabView").getDeclaredField("textView"); textField.setAccessible(true);
                Integer baseline = null;
                for (View tab : tabs) {
                    check(tab.getContentDescription() != null && tab.getContentDescription().length() > 0, "Navigation icon has no accessible name");
                    TextView text = (TextView) textField.get(tab);
                    check((text.getVisibility() == View.VISIBLE) == labels, "Label visibility is inconsistent");
                    if (labels) {
                        int[] origin = new int[2]; text.getLocationOnScreen(origin); int current = origin[1] + text.getBaseline();
                        if (baseline == null) baseline = current; else check(Math.abs(current - baseline) <= 1, "Label baselines do not match");
                        check(text.getTop() - tab.getPaddingTop() == dp(32), "Navigation label was not lowered");
                        check(text.getBottom() <= tab.getHeight() - tab.getPaddingBottom(), "Label is clipped at the bottom");
                    } else {
                        for (int i = 0; i < ((ViewGroup) tab).getChildCount(); i++) {
                            View icon = ((ViewGroup) tab).getChildAt(i);
                            if (icon == text || icon.getVisibility() != View.VISIBLE) continue;
                            check(Math.abs(icon.getTop() + icon.getHeight() / 2f - tab.getHeight() / 2f) <= 1, "Icon is not centered without labels");
                        }
                    }
                }
            } catch (Exception e) { throw new RuntimeException(e); } });
            save("navigation-" + mode + "-" + (labels ? "on" : "off") + ".png");
        } finally { main(() -> dialog[0].dismiss()); }
    }
    private int string(String name) { return context.getResources().getIdentifier(name, "string", "ksnxdev.calamus"); }
    private TextView findText(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = findText(((ViewGroup) view).getChildAt(i), value); if (found != null) return found; }
        return null;
    }
}
