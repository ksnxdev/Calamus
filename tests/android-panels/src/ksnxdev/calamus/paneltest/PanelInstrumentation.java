package ksnxdev.calamus.paneltest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

public class PanelInstrumentation extends Instrumentation {
    private ClassLoader loader;
    private Class<?> theme;
    private Class<?> material;
    private Class<?> provider;
    private Context context;

    private Class<?> type(String name) throws Exception {
        return Class.forName(name, true, loader);
    }

    private int key(String name) throws Exception {
        return theme.getField("key_" + name).getInt(null);
    }

    private int color(String name) throws Exception {
        return (int) theme.getMethod("getColor", int.class).invoke(null, key(name));
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext();
            loader = context.getClassLoader();
            final Throwable[] error = {null};
            runOnMainSync(() -> {
                try {
                    type("org.telegram.messenger.ApplicationLoader").getMethod("postInitApplication").invoke(null);
                    theme = type("org.telegram.ui.ActionBar.Theme");
                    material = type("uz.ksnxdev.calamus.CalamusMaterial");
                    provider = type("org.telegram.ui.ActionBar.Theme$ResourcesProvider");
                    SharedPreferences prefs = context.getSharedPreferences("calamus_config", 0);
                    boolean existed = prefs.contains("translucent_elements");
                    boolean saved = prefs.getBoolean("translucent_elements", true);
                    Object previous = theme.getMethod("getCurrentTheme").invoke(null);
                    Method apply = theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class);
                    boolean previousDark = (boolean) previous.getClass().getMethod("isDark").invoke(previous);
                    try {
                        for (boolean dark : new boolean[]{false, true}) {
                            Object info = theme.getMethod("getTheme", String.class).invoke(null, dark ? "Monet Dark" : "Monet Light");
                            apply.invoke(null, info, false, dark);
                            for (boolean translucent : new boolean[]{true, false}) {
                                prefs.edit().putBoolean("translucent_elements", translucent).commit();
                                render(dark, translucent);
                            }
                        }
                    } finally {
                        SharedPreferences.Editor editor = prefs.edit();
                        if (existed) editor.putBoolean("translucent_elements", saved);
                        else editor.remove("translucent_elements");
                        editor.commit();
                        apply.invoke(null, previous, false, previousDark);
                    }
                } catch (Throwable failure) {
                    error[0] = failure;
                }
            });
            if (error[0] != null) throw new RuntimeException(error[0]);
            previewSettings();
            previewFrostedPanels();
            result.putString("stream", "Panel rendering passed: light/dark, translucent/opaque, pixel composition, hardware blur and appearance switch verified\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(0, result);
        }
    }

    private void previewSettings() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("calamus_config", 0);
        boolean existed = prefs.contains("translucent_elements");
        boolean saved = prefs.getBoolean("translucent_elements", true);
        android.content.Intent intent = new android.content.Intent().setClassName("ksnxdev.calamus", "org.telegram.ui.LaunchActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        android.app.Activity activity = null;
        for (int i = 0; i < 100 && activity == null; i++) {
            Thread.sleep(100);
            activity = (android.app.Activity) type("org.telegram.ui.LaunchActivity").getField("instance").get(null);
        }
        if (activity == null) throw new AssertionError("Launch activity did not start");
        final android.app.Activity launch = activity;
        final Throwable[] error = {null};
        try {
            prefs.edit().putBoolean("translucent_elements", true).commit();
            runOnMainSync(() -> {
                try {
                    Object fragment = type("uz.unnarsx.cherrygram.preferences.AppearancePreferencesEntry").getConstructor().newInstance();
                    launch.getClass().getMethod("presentFragment", type("org.telegram.ui.ActionBar.BaseFragment")).invoke(launch, fragment);
                } catch (Throwable failure) {
                    error[0] = failure;
                }
            });
            if (error[0] != null) throw new RuntimeException(error[0]);
            Thread.sleep(1500);
            saveWindow("appearance-translucency-on.png");
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Translucent elements");
            if (nodes.isEmpty()) throw new AssertionError("Translucency setting is missing from appearance screen");
            android.graphics.Rect bounds = new android.graphics.Rect();
            nodes.get(0).getBoundsInScreen(bounds);
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
                    bounds.centerX(), bounds.centerY(), 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP,
                    bounds.centerX(), bounds.centerY(), 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            getUiAutomation().injectInputEvent(down, true);
            getUiAutomation().injectInputEvent(up, true);
            down.recycle();
            up.recycle();
            Thread.sleep(1000);
            if (prefs.getBoolean("translucent_elements", true)) throw new AssertionError("Switch did not persist disabled state");
            saveWindow("appearance-translucency-off.png");
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (existed) editor.putBoolean("translucent_elements", saved);
            else editor.remove("translucent_elements");
            editor.commit();
        }
    }

    private void previewFrostedPanels() throws Exception {
        final android.app.Activity launch = (android.app.Activity) type("org.telegram.ui.LaunchActivity").getField("instance").get(null);
        SharedPreferences prefs = context.getSharedPreferences("calamus_config", 0);
        boolean saved = prefs.getBoolean("translucent_elements", true);
        Object previous = theme.getMethod("getCurrentTheme").invoke(null);
        boolean previousDark = (boolean) previous.getClass().getMethod("isDark").invoke(previous);
        final Throwable[] error = {null};
        try {
            for (boolean dark : new boolean[]{false, true}) {
                for (boolean enabled : new boolean[]{true, false}) {
                    final android.app.Dialog[] window = {null};
                    final View[] backdrop = {null};
                    runOnMainSync(() -> {
                        try {
                            prefs.edit().putBoolean("translucent_elements", enabled).commit();
                            Object info = theme.getMethod("getTheme", String.class).invoke(null, dark ? "Monet Dark" : "Monet Light");
                            theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, info, false, dark);
                            android.app.Dialog dialog = new android.app.Dialog(launch);
                            window[0] = dialog;
                            FrameLayout root = new FrameLayout(launch);
                            backdrop[0] = new View(launch) {
                                @Override protected void onDraw(Canvas canvas) { pattern(canvas); }
                            };
                            root.addView(backdrop[0], new FrameLayout.LayoutParams(-1, -1));
                            Object source = type("org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode").getConstructor(type("org.telegram.ui.Components.blur3.source.BlurredBackgroundSource")).newInstance(new Object[]{null});
                            Canvas recording = (Canvas) source.getClass().getMethod("beginRecording", int.class, int.class).invoke(source, 720, 1600);
                            pattern(recording);
                            source.getClass().getMethod("endRecording").invoke(source);
                            Class<?> base = type("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable");
                            for (int y : new int[]{240, 1040}) {
                                Drawable drawable = (Drawable) source.getClass().getMethod("createDrawable").invoke(source);
                                Object colors = type("org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed").getConstructor(provider, int.class).newInstance(null, key("dialogBackground"));
                                base.getMethod("setColorProvider", type("org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider")).invoke(drawable, colors);
                                base.getMethod("setTranslucentPanel", boolean.class).invoke(drawable, true);
                                base.getMethod("setRadius", float.class).invoke(drawable, 40f);
                                base.getMethod("setSourceOffset", float.class, float.class).invoke(drawable, 24f, (float)y);
                                View panel = new View(launch);
                                if (y == 1040) drawable = (Drawable) material.getMethod("createNavigationBackground", provider, Drawable.class).invoke(null, null, drawable);
                                panel.setBackground(drawable);
                                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(672, 160);
                                params.leftMargin = 24;
                                params.topMargin = y;
                                root.addView(panel, params);
                            }
                            dialog.setContentView(root);
                            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                            dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                            dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                            dialog.show();
                            dialog.getWindow().setLayout(-1, -1);
                        } catch (Throwable failure) { error[0] = failure; }
                    });
                    if (error[0] != null) throw new RuntimeException(error[0]);
                    try {
                        Thread.sleep(800);
                        Bitmap screenshot = getUiAutomation().takeScreenshot();
                        int[] origin = new int[2];
                        runOnMainSync(() -> backdrop[0].getLocationOnScreen(origin));
                        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "frosted-diagnostic.png"))) {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
                        }
                        for (int y : new int[]{320, 1120}) {
                            int low = 255;
                            int high = 0;
                            for (int x = 120; x < 260; x++) {
                                int red = Color.red(screenshot.getPixel(origin[0] + x, origin[1] + y));
                                low = Math.min(low, red);
                                high = Math.max(high, red);
                            }
                            if (high - low > (enabled ? 25 : 1)) throw new AssertionError("Sharp stripes remain behind panel: " + (high - low));
                            int a = screenshot.getPixel(origin[0] + 440, origin[1] + y);
                            int b = screenshot.getPixel(origin[0] + 600, origin[1] + y);
                            int difference = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.blue(a) - Color.blue(b));
                            if (enabled && difference < 40) throw new AssertionError("Backdrop is not visible through panel");
                            if (!enabled && difference > 3) throw new AssertionError("Disabled blur must be opaque");
                        }
                        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "frosted-" + (dark ? "dark" : "light") + "-" + (enabled ? "on" : "off") + ".png"))) {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
                        }
                        screenshot.recycle();
                    } finally {
                        runOnMainSync(() -> window[0].dismiss());
                    }
                }
            }
        } finally {
            prefs.edit().putBoolean("translucent_elements", saved).commit();
            runOnMainSync(() -> {
                try {
                    theme.getMethod("applyTheme", type("org.telegram.ui.ActionBar.Theme$ThemeInfo"), boolean.class, boolean.class).invoke(null, previous, false, previousDark);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
        }
    }

    private static void pattern(Canvas canvas) {
        Paint paint = new Paint();
        for (int x = 0; x < 360; x += 12) {
            paint.setColor((x / 12 % 2) == 0 ? Color.BLACK : Color.WHITE);
            canvas.drawRect(x, 0, x + 12, 1600, paint);
        }
        paint.setColor(0xff2864b0);
        canvas.drawRect(360, 0, 520, 1600, paint);
        paint.setColor(0xffd68a38);
        canvas.drawRect(520, 0, 720, 1600, paint);
    }

    private void saveWindow(String name) throws Exception {
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new AssertionError("Window screenshot failed");
        try (FileOutputStream stream = new FileOutputStream(new File(context.getFilesDir(), name))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        screenshot.recycle();
    }

    private static int backdrop(int x, int y) {
        return ((x / 96 + y / 96) & 1) == 0 ? 0xffa87144 : 0xff416c83;
    }

    private void render(boolean dark, boolean translucent) throws Exception {
        FrameLayout root = new FrameLayout(context);
        root.addView(new View(context) {
            private final Paint paint = new Paint();
            @Override
            protected void onDraw(Canvas canvas) {
                for (int y = 0; y < 960; y += 96) {
                    for (int x = 0; x < 720; x += 96) {
                        paint.setColor(backdrop(x, y));
                        canvas.drawRect(x, y, x + 96, y + 96, paint);
                    }
                }
            }
        }, new FrameLayout.LayoutParams(720, 960));
        TextView title = new TextView(context);
        title.setText((dark ? "Dark" : "Light") + " / translucent " + (translucent ? "ON" : "OFF"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new FrameLayout.LayoutParams(720, 100));

        Object source = type("org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor").getConstructor().newInstance();
        Drawable folder = (Drawable) source.getClass().getMethod("createDrawable").invoke(source);
        Class<?> base = type("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable");
        Object colors = type("org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed")
                .getConstructor(provider, int.class).newInstance(null, key("dialogBackground"));
        base.getMethod("setColorProvider", type("org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider")).invoke(folder, colors);
        base.getMethod("setRadius", float.class).invoke(folder, 36f);
        base.getMethod("setTranslucentPanel", boolean.class).invoke(folder, true);
        FrameLayout top = new FrameLayout(context);
        top.setBackground(folder);
        TextView folders = new TextView(context);
        folders.setText("Все чаты        Личные        Работа");
        folders.setTextColor(color("windowBackgroundWhiteBlackText"));
        folders.setTextSize(16);
        folders.setTypeface(Typeface.DEFAULT_BOLD);
        folders.setGravity(Gravity.CENTER);
        top.addView(folders, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(672, 120);
        topParams.leftMargin = 24;
        topParams.topMargin = 120;
        root.addView(top, topParams);

        View navigation = (View) type("org.telegram.ui.MainTabsLayout").getConstructor(Context.class, provider).newInstance(context, null);
        navigation.setBackground((Drawable) material.getMethod("createNavigationBackground", provider).invoke(null, new Object[]{null}));
        navigation.setPadding(4, 36, 4, 36);
        Class<?> tabs = type("org.telegram.ui.Components.glass.GlassTabView");
        String[] labels = {"Settings", "MainTabsChats", "Search"};
        int[] icons = {android.R.drawable.ic_menu_preferences, android.R.drawable.ic_dialog_email, android.R.drawable.ic_menu_search};
        for (int i = 0; i < 3; i++) {
            int stringId = context.getResources().getIdentifier(labels[i], "string", "ksnxdev.calamus");
            View tab = (View) tabs.getMethod("createStaticTab", Context.class, provider, int.class, int.class, boolean.class)
                    .invoke(null, context, null, icons[i], stringId, true);
            ((android.view.ViewGroup) navigation).addView(tab);
            navigation.getClass().getMethod("setViewVisible", View.class, boolean.class, boolean.class).invoke(navigation, tab, true, false);
            if (i == 1) tabs.getMethod("setSelected", boolean.class, boolean.class).invoke(tab, true, false);
        }
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(672, 160);
        navParams.leftMargin = 24;
        navParams.topMargin = 600;
        root.addView(navigation, navParams);
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 720, 960);
        Bitmap bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        int fill = (int) material.getMethod("panelColor", int.class).invoke(null, color("dialogBackground"));
        Method composite = type("androidx.core.graphics.ColorUtils").getMethod("compositeColors", int.class, int.class);
        for (int y : new int[]{128, 608}) {
            int expected = (int) composite.invoke(null, fill, backdrop(360, y));
            int actual = bitmap.getPixel(360, y);
            if (Math.abs(Color.red(expected) - Color.red(actual)) > 1 || Math.abs(Color.green(expected) - Color.green(actual)) > 1 || Math.abs(Color.blue(expected) - Color.blue(actual)) > 1) {
                throw new AssertionError("Panel pixel mismatch at " + y + ": " + Integer.toHexString(actual) + " expected " + Integer.toHexString(expected));
            }
        }
        File file = new File(context.getFilesDir(), "panel-render-" + (dark ? "dark" : "light") + "-" + (translucent ? "on" : "off") + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        bitmap.recycle();
    }
}
