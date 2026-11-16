package ksnxdev.calamus.paneltest;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class StartupInstrumentation extends SettingsInstrumentation {
    private AccessibilityNodeInfo denyButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(java.util.Locale.ROOT).replace('’', '\'');
        if (text.equals("don't allow") || text.equals("deny")) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = denyButton(node.getChild(i)); if (found != null) return found; }
        return null;
    }
    private void dismissOptionalPermissions(Object login) throws Exception {
        for (int i = 0; i < 5; i++) {
            for (String name : new String[]{"permissionsDialog", "permissionsShowDialog"}) {
                android.app.Dialog rationale = (android.app.Dialog) field(login, name);
                if (rationale != null && rationale.isShowing()) { main(rationale::dismiss); Thread.sleep(400); }
            }
            AccessibilityNodeInfo button = denyButton(getUiAutomation().getRootInActiveWindow());
            if (button == null) { Thread.sleep(300); button = denyButton(getUiAutomation().getRootInActiveWindow()); }
            if (button == null) return;
            check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Could not decline optional phone permission");Thread.sleep(400);
        }
    }
    private View find(View view, Class<?> kind) {
        if (kind.isInstance(view)) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View match = find(group.getChildAt(i), kind); if (match != null) return match;
        }
        return null;
    }
    private String texts(View view) {
        StringBuilder value = new StringBuilder();
        if (view instanceof TextView text) value.append(text.getText()).append('\n');
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) value.append(texts(group.getChildAt(i)));
        return value.toString();
    }
    private Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext(); loader = context.getClassLoader();
            main(() -> { try { type("org.telegram.messenger.ApplicationLoader").getMethod("postInitApplication").invoke(null); } catch (Exception e) { throw new RuntimeException(e); } });
            Class<?> config = type("org.telegram.messenger.UserConfig");
            for (int i = 0; i < config.getField("MAX_ACCOUNT_COUNT").getInt(null); i++) check(!(boolean) config.getMethod("getInstance", int.class).invoke(null, i).getClass().getMethod("isClientActivated").invoke(config.getMethod("getInstance", int.class).invoke(null, i)), "Startup checks require an unauthenticated emulator");
            context.startActivity(new Intent().setClassName("ksnxdev.calamus", "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for (int i = 0; i < 100 && activity == null; i++) { Thread.sleep(100); activity = (android.app.Activity) type("org.telegram.ui.LaunchActivity").getField("instance").get(null); }
            check(activity != null, "No launch activity"); theme = type("org.telegram.ui.ActionBar.Theme"); Object previous = theme.getMethod("getCurrentTheme").invoke(null);
            SharedPreferences loginPrefs = context.getSharedPreferences("logininfo2", 0); Map<String, ?> saved = new HashMap<>(loginPrefs.getAll());
            int previousAutoNight = theme.getField("selectedAutoNightType").getInt(null); theme.getField("selectedAutoNightType").setInt(null, 0);
            try {
                for (boolean dark : new boolean[]{false, true}) {
                    main(() -> { try { Object info = theme.getMethod("getTheme", String.class).invoke(null, dark ? "Monet Dark" : "Monet Light"); theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, info, false, dark); } catch (Exception e) { throw new RuntimeException(e); } });
                    Object intro = open("org.telegram.ui.IntroActivity"); View root = (View) type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getFragmentView").invoke(intro);
                    String labels = texts(root); check(labels.contains("Powered by Cherrygram") && !labels.contains("TelegramAPI"), "Unexpected intro footer");
                    TextView start = (TextView) field(intro, "startMessagingButton"), credit = (TextView) field(intro, "poweredByTextView");
                    int[] startPoint = new int[2], creditPoint = new int[2]; start.getLocationOnScreen(startPoint); credit.getLocationOnScreen(creditPoint);
                    check(creditPoint[1] > startPoint[1] + start.getHeight(), "Cherrygram credit overlaps the start button");save("startup-intro-" + (dark ? "dark" : "light") + ".png");
                    loginPrefs.edit().clear().commit(); main(start::performClick);
                    Object login = null;
                    for (int i = 0; i < 100; i++) { Thread.sleep(100); Object last = type("org.telegram.ui.LaunchActivity").getMethod("getLastFragment").invoke(null); if (last != null && last.getClass().getSimpleName().equals("LoginActivity")) { login = last; break; } }
                    check(login != null, "Start button did not open login"); Thread.sleep(700);dismissOptionalPermissions(login);
                    main(() -> { try { Object info = theme.getMethod("getTheme", String.class).invoke(null, dark ? "Monet Dark" : "Monet Light"); theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, info, false, dark); activity.getClass().getMethod("rebuildAllFragments", boolean.class).invoke(activity, true); } catch (Exception e) { throw new RuntimeException(e); } });Thread.sleep(500);
                    check((boolean) theme.getMethod("isCurrentThemeDark").invoke(null) == dark, "Login theme does not match test mode");
                    View phoneRoot = (View) type("org.telegram.ui.ActionBar.BaseFragment").getMethod("getFragmentView").invoke(login);
                    TextView phone = (TextView) find(phoneRoot, type("org.telegram.ui.Components.AnimatedPhoneNumberEditText"));check(phone != null && phone.getVisibility() == View.VISIBLE, "Phone input unavailable");check(phone.getCurrentTextColor() != phone.getCurrentHintTextColor(), "Phone placeholder and typed digits have the same color");
                    for (String name : new String[]{"permissionsDialog", "permissionsShowDialog"}) { android.app.Dialog dialog = (android.app.Dialog) field(login, name); check(dialog == null || !dialog.isShowing(), "Phone permission explanation still covers login"); }
                    save("startup-login-" + (dark ? "dark" : "light") + ".png");Object current = login;
                    main(() -> { try { type("org.telegram.ui.ActionBar.BaseFragment").getMethod("finishFragment", boolean.class).invoke(current, false); } catch (Exception e) { throw new RuntimeException(e); } });Thread.sleep(400);
                }
            } finally {
                SharedPreferences.Editor restore = loginPrefs.edit().clear();
                for (Map.Entry<String, ?> entry : saved.entrySet()) {
                    Object value = entry.getValue(); String key = entry.getKey();
                    if (value instanceof String v) restore.putString(key, v); else if (value instanceof Boolean v) restore.putBoolean(key, v); else if (value instanceof Integer v) restore.putInt(key, v); else if (value instanceof Long v) restore.putLong(key, v); else if (value instanceof Float v) restore.putFloat(key, v); else if (value instanceof Set) restore.putStringSet(key, (Set<String>) value);
                }
                restore.commit();main(() -> { try { theme.getField("selectedAutoNightType").setInt(null, previousAutoNight);theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, previous, false, (boolean) previous.getClass().getMethod("isDark").invoke(previous)); } catch (Exception e) { throw new RuntimeException(e); } });
            }
            result.putString("stream", "Startup checks passed: light/dark intro, Cherrygram credit, login navigation and phone placeholder contrast; no login request submitted.\n");finish(-1, result);
        } catch (Throwable failure) { result.putString("stream", android.util.Log.getStackTraceString(failure));finish(0, result); }
    }
}
