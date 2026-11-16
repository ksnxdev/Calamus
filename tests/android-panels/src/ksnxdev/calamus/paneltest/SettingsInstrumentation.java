package ksnxdev.calamus.paneltest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class SettingsInstrumentation extends Instrumentation {
    protected Context context;
    protected Activity activity;
    protected ClassLoader loader;
    protected Class<?> theme;
    protected SharedPreferences prefs;

    protected Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    protected void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    protected void main(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext(); loader = context.getClassLoader();
            main(() -> { try { type("org.telegram.messenger.ApplicationLoader").getMethod("postInitApplication").invoke(null); } catch (Exception e) { throw new RuntimeException(e); } });
            context.startActivity(new Intent().setClassName("ksnxdev.calamus", "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for (int i = 0; i < 100 && activity == null; i++) { Thread.sleep(100); activity = (Activity) type("org.telegram.ui.LaunchActivity").getField("instance").get(null); }
            check(activity != null, "Launch activity missing");
            theme = type("org.telegram.ui.ActionBar.Theme");
            prefs = context.getSharedPreferences("calamus_config", 0);
            boolean hadSystem = prefs.contains("use_system_color"), oldSystem = prefs.getBoolean("use_system_color", true);
            boolean hadColor = prefs.contains("app_color"); int oldColor = prefs.getInt("app_color", 0xff677e6d);
            Object oldTheme = theme.getMethod("getCurrentTheme").invoke(null);
            try {
                for (boolean dark : new boolean[]{false, true}) verify(dark);
            } finally {
                SharedPreferences.Editor editor = prefs.edit();
                if (hadSystem) editor.putBoolean("use_system_color", oldSystem); else editor.remove("use_system_color");
                if (hadColor) editor.putInt("app_color", oldColor); else editor.remove("app_color");
                editor.commit();
                main(() -> { try { theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, oldTheme, false, (boolean) oldTheme.getClass().getMethod("isDark").invoke(oldTheme)); } catch (Exception e) { throw new RuntimeException(e); } });
            }
            result.putString("stream", "Settings checks passed: light/dark, branding, channel, plugin menu, system/manual colors, apply/cancel and persistence\n");
            finish(-1, result);
        } catch (Throwable failure) { result.putString("stream", android.util.Log.getStackTraceString(failure)); finish(0, result); }
    }

    private void verify(boolean dark) throws Exception {
        String mode = dark ? "dark" : "light";
        prefs.edit().putBoolean("use_system_color", true).putInt("app_color", 0xff4675bb).commit();
        main(() -> { try { Object info = theme.getMethod("getTheme", String.class).invoke(null, dark ? "Monet Dark" : "Monet Light"); theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, info, false, dark); } catch (Exception e) { throw new RuntimeException(e); } });
        Object settings = open("uz.unnarsx.cherrygram.preferences.CGPreferencesEntry");
        String labels = labels(settings);
        check(labels.contains("About Calamus") && !labels.contains("Donate") && !labels.contains("Support"), "Calamus settings branding/support rows: " + labels);
        save("calamus-settings-" + mode + ".png");
        Object about = open("uz.unnarsx.cherrygram.preferences.AboutPreferencesEntry");
        labels = labels(about);
        check(labels.contains("@CalamusGram") && !labels.contains("Beta channel") && !labels.contains("chat"), "Old chat links in about screen: " + labels);
        save("calamus-about-" + mode + ".png");
        Object plugins = open("ksnxdev.calamus.plugins.ui.PluginsActivity");
        labels = labels(plugins);
        for (String unwanted : new String[]{"example plugin", "Isolation is always", "Installation and updates", "Sending messages"}) check(!labels.contains(unwanted), "Removed plugin content remains: " + unwanted);
        save("calamus-plugins-" + mode + ".png");
        Object appearance = open("uz.unnarsx.cherrygram.preferences.AppearancePreferencesEntry");
        check(!rowEnabled(appearance, "appColor"), "Manual color enabled while system color is on");
        int systemColor = (int) type("uz.ksnxdev.calamus.CalamusMaterial").getMethod("seedColor").invoke(null);
        save("calamus-color-" + mode + "-system.png");
        tap("Use system color");
        check(!prefs.getBoolean("use_system_color", true), "System color switch did not persist");
        appearance = type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(null);
        check(rowEnabled(appearance, "appColor"), "Manual color not enabled");
        tapColorRow();
        EditText[] editor = new EditText[1];
        main(() -> editor[0] = hexEditor(activity.getWindow().getDecorView()));
        if (editor[0] == null) {
            Object fragment = appearance;
            main(() -> { try { Object dialog = type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getVisibleDialog").invoke(fragment); editor[0] = hexEditor(((android.app.Dialog) dialog).getWindow().getDecorView()); } catch (Exception e) { throw new RuntimeException(e); } });
        }
        check(editor[0] != null, "Color hex input missing");
        main(() -> editor[0].setText("BB5544"));
        Thread.sleep(300);
        save("calamus-color-" + mode + "-picker.png");
        tap("Cancel"); check(prefs.getInt("app_color", 0) == 0xff4675bb, "Cancel changed saved color");
        tapColorRow();
        Object fragment = type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(null);
        main(() -> { try { android.app.Dialog dialog = (android.app.Dialog) type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getVisibleDialog").invoke(fragment); editor[0] = hexEditor(dialog.getWindow().getDecorView()); editor[0].setText("BB5544"); } catch (Exception e) { throw new RuntimeException(e); } });
        tap("Apply");
        check(prefs.getInt("app_color", 0) == 0xffbb5544, "Applied color not saved");
        check((int) type("uz.ksnxdev.calamus.CalamusMaterial").getMethod("seedColor").invoke(null) == 0xffbb5544, "Palette did not use custom seed");
        int customBackground = themeColor("windowBackgroundGray");
        save("calamus-color-" + mode + "-manual.png");
        appearance = open("uz.unnarsx.cherrygram.preferences.AppearancePreferencesEntry");
        check(rowEnabled(appearance, "appColor") && labels(appearance).contains("#BB5544"), "Manual choice lost when reopening settings");
        tap("Use system color");
        check(prefs.getBoolean("use_system_color", false), "System color did not re-enable");
        check((int) type("uz.ksnxdev.calamus.CalamusMaterial").getMethod("seedColor").invoke(null) == systemColor, "System seed did not restore");
        check(customBackground != themeColor("windowBackgroundGray"), "Background does not respond to chosen color");
        check(prefs.getInt("app_color", 0) == 0xffbb5544, "System switch erased custom color");
        Object menuConfig = type("uz.unnarsx.cherrygram.core.configs.CherrygramMessagesConfig").getField("INSTANCE").get(null);
        boolean oldMenu = (boolean) menuConfig.getClass().getMethod("getBlurMessageMenuBackground").invoke(menuConfig);
        try {
            Object messageMenu = open("uz.unnarsx.cherrygram.preferences.MessageMenuPreferencesEntry");
            boolean found = false;
            for (Object item : items(messageMenu)) if ("iosMsgMenu".equals(item.getClass().getField("slug").get(item))) {
                check(!item.getClass().getField("locked").getBoolean(item), "iOS menu is still locked behind donations");found = true;
            }
            check(found, "iOS message menu switch missing");
            tap("iOS-styled message menu");
            check((boolean) menuConfig.getClass().getMethod("getBlurMessageMenuBackground").invoke(menuConfig) != oldMenu, "iOS menu switch requires a donation or did not persist");
            save("ios-message-settings-" + mode + ".png");
            tap("iOS-styled message menu");
            check((boolean) menuConfig.getClass().getMethod("getBlurMessageMenuBackground").invoke(menuConfig) == oldMenu, "iOS menu switch did not restore");
        } finally { menuConfig.getClass().getMethod("setBlurMessageMenuBackground", boolean.class).invoke(menuConfig, oldMenu); }
    }

    private int themeColor(String name) throws Exception { return (int) theme.getMethod("getColor", int.class).invoke(null, theme.getField("key_" + name).getInt(null)); }
    protected Object open(String name) throws Exception {
        Object fragment = type(name).getConstructor().newInstance();
        main(() -> { try { activity.getClass().getMethod("presentFragment", type("org.telegram.ui.ActionBar.BaseFragment"), boolean.class, boolean.class).invoke(activity, fragment, false, true); } catch (Exception e) { throw new RuntimeException(e); } });
        Thread.sleep(1000); return fragment;
    }
    private ArrayList<?> items(Object fragment) throws Exception {
        ArrayList<Object> rows = new ArrayList<>();
        Method fill = fragment.getClass().getDeclaredMethod("fillItems", ArrayList.class, type("org.telegram.ui.Components.UniversalAdapter")); fill.setAccessible(true);
        main(() -> { try { fill.invoke(fragment, rows, null); } catch (Exception e) { throw new RuntimeException(e); } }); return rows;
    }
    private String labels(Object fragment) throws Exception {
        StringBuilder text = new StringBuilder();
        for (Object item : items(fragment)) for (String field : new String[]{"text", "subtext", "textValue"}) { try { text.append(item.getClass().getField(field).get(item)).append('\n'); } catch (NoSuchFieldException ignored) {} }
        return text.toString();
    }
    private boolean rowEnabled(Object fragment, String slug) throws Exception {
        for (Object item : items(fragment)) if (slug.equals(item.getClass().getField("slug").get(item))) return item.getClass().getField("enabled").getBoolean(item);
        throw new AssertionError("Missing row " + slug);
    }
    private EditText hexEditor(View view) {
        if (view instanceof EditText && ((EditText) view).getText().toString().matches("[0-9A-Fa-f]{6}")) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { EditText found = hexEditor(((ViewGroup) view).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private void tapColorRow() throws Exception {
        Object fragment = type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(null);
        Field field = type("uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry").getDeclaredField("listView"); field.setAccessible(true);
        Object list = field.get(fragment);
        Rect bounds = new Rect();
        main(() -> {
            try {
                int position = (int) list.getClass().getMethod("findPositionByItemSlug", String.class).invoke(list, "appColor");
                Object manager = list.getClass().getMethod("getLayoutManager").invoke(list);
                View row = (View) manager.getClass().getMethod("findViewByPosition", int.class).invoke(manager, position);
                check(row != null && row.getGlobalVisibleRect(bounds), "Color row is not visible");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        injectTap(bounds);
    }
    protected void injectTap(Rect rect) throws Exception {
        long time = android.os.SystemClock.uptimeMillis();
        for (int action : new int[]{0, 1}) { android.view.MotionEvent event = android.view.MotionEvent.obtain(time, time + action * 80, action, rect.centerX(), rect.centerY(), 0); event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); getUiAutomation().injectInputEvent(event, true); event.recycle(); }
        Thread.sleep(1000);
    }
    private void tap(String label) throws Exception {
        List<AccessibilityNodeInfo> nodes = getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(label);
        if (nodes.isEmpty() && label.startsWith("#")) nodes = getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("App color");
        check(!nodes.isEmpty(), "Missing visible control: " + label);
        Rect rect = new Rect(); nodes.get(nodes.size() - 1).getBoundsInScreen(rect);
        injectTap(rect);
    }
    protected void save(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } bitmap.recycle();
    }
}
