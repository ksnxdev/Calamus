package ksnxdev.calamus.access;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

public final class RestrictedAccountActivity extends Activity {
    private TextView retry, status;
    private final Runnable listener = this::update;
    private String text(int key) { return LocaleController.getString(key); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); ApplicationLoader.postInitApplication();
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> moveTaskToBack(true));
        if (!CalamusAccountAccess.isBlocked(UserConfig.selectedAccount)) { finish(); return; }
        if (CalamusAccountAccess.requiresUnlock()) { unlock(); return; }
        int background = Theme.getColor(Theme.key_windowBackgroundGray), primary = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(background);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER); content.setPadding(AndroidUtilities.dp(28),AndroidUtilities.dp(40),AndroidUtilities.dp(28),AndroidUtilities.dp(40));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-1)); setContentView(scroll);
        updateSystemBars();
        scroll.post(this::updateSystemBars);
        ImageView logo = new ImageView(this); logo.setImageResource(R.mipmap.icon_launcher_cherry); content.addView(logo,new LinearLayout.LayoutParams(AndroidUtilities.dp(80),AndroidUtilities.dp(80)));
        TextView title = label(text(R.string.CalamusAccessRestricted),24,primary); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); add(content,title,24);
        add(content,label(text(R.string.CalamusAccessRestrictedInfo),16,Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)),16);
        add(content,label("ID: " + UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId(),14,Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)),12);
        retry = button(text(R.string.CalamusAccessRetry)); retry.setOnClickListener(view -> CalamusAccountAccess.refresh(true)); add(content,retry,28);
        status = label("",14,Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)); add(content,status,16);
        update();
    }
    private void updateSystemBars() {
        int background = Theme.getColor(Theme.key_windowBackgroundGray);
        boolean dark = AndroidUtilities.computePerceivedBrightness(background) < .5f;
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        View decor = getWindow().getDecorView();
        int legacyMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~legacyMask : decor.getSystemUiVisibility() | legacyMask);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (controller != null) controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) updateSystemBars(); }
    private TextView label(String value,int size,int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER); return view;
    }
    private TextView button(String value) {
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        TextView view = label(value,16,Theme.getColor(Theme.key_featuredStickers_buttonText));
        view.setTypeface(Typeface.DEFAULT,Typeface.BOLD); view.setGravity(Gravity.CENTER); view.setMinHeight(AndroidUtilities.dp(52)); view.setPadding(AndroidUtilities.dp(16),AndroidUtilities.dp(12),AndroidUtilities.dp(16),AndroidUtilities.dp(12));
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(26),accent,Theme.getColor(Theme.key_listSelector)));
        return view;
    }
    private void add(LinearLayout parent,View view,int top) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin = AndroidUtilities.dp(top); parent.addView(view,params); }
    private void unlock() { if (!isFinishing()) { startActivity(new Intent(this,LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); finish(); } }
    private void update() {
        if (isFinishing()) return;
        if (CalamusAccountAccess.requiresUnlock()) { unlock(); return; }
        if (!CalamusAccountAccess.isBlocked(UserConfig.selectedAccount)) { finish(); return; }
        if (retry == null) return;
        retry.setEnabled(!CalamusAccountAccess.updating()); retry.setAlpha(retry.isEnabled() ? 1f : .6f);
        retry.setText(text(CalamusAccountAccess.updating() ? R.string.CalamusAccessChecking : R.string.CalamusAccessRetry));
        status.setText(CalamusAccountAccess.hasError() ? text(R.string.CalamusAccessRefreshFailed) : "");
    }
    @Override protected void onStart() { super.onStart(); CalamusAccountAccess.addListener(listener); }
    @Override protected void onStop() { CalamusAccountAccess.removeListener(listener); super.onStop(); }
    @Override protected void onResume() { super.onResume(); update(); }
    @Override public void onBackPressed() { moveTaskToBack(true); }
}
