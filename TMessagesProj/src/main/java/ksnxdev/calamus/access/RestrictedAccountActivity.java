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
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;
import java.util.ArrayList;

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
        retry = button(text(R.string.CalamusAccessRetry),true); retry.setOnClickListener(view -> CalamusAccountAccess.refresh(true)); add(content,retry,28);
        TextView accounts = button(text(R.string.CalamusAccessChooseAccount),false); accounts.setOnClickListener(view -> chooseAccount()); add(content,accounts,12);
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
    private TextView button(String value,boolean primary) {
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        TextView view = label(value,16,primary ? Theme.getColor(Theme.key_featuredStickers_buttonText) : accent);
        view.setTypeface(Typeface.DEFAULT,Typeface.BOLD); view.setGravity(Gravity.CENTER); view.setMinHeight(AndroidUtilities.dp(52)); view.setPadding(AndroidUtilities.dp(16),AndroidUtilities.dp(12),AndroidUtilities.dp(16),AndroidUtilities.dp(12));
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(26),primary ? accent : Theme.getColor(Theme.key_windowBackgroundWhite),Theme.getColor(Theme.key_listSelector)));
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
    private void chooseAccount() {
        ArrayList<CharSequence> labels = new ArrayList<>(); ArrayList<Integer> accounts = new ArrayList<>(); int available = -1;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) { if (available < 0) available = a; continue; }
            if (CalamusAccountAccess.isBlocked(a)) continue;
            labels.add(UserObject.getUserName(config.getCurrentUser())); accounts.add(a);
        }
        if (available >= 0 && LaunchActivity.instance != null) { labels.add(text(R.string.AddAccount)); accounts.add(available); }
        if (labels.isEmpty()) { new AlertDialog.Builder(this).setMessage(text(R.string.CalamusAccessNoOtherAccounts)).setPositiveButton(text(R.string.OK),null).show(); return; }
        new AlertDialog.Builder(this).setTitle(text(R.string.CalamusAccessChooseAccount)).setItems(labels.toArray(new CharSequence[0]),(window,index) -> {
            int account = accounts.get(index); LaunchActivity launch = LaunchActivity.instance;
            if (!UserConfig.getInstance(account).isClientActivated()) {
                if (launch != null) { launch.presentFragment(new LoginActivity(account)); finish(); }
            } else {
                if (launch != null) launch.switchToAccount(account,true);
                else { UserConfig.selectedAccount = account; UserConfig.getInstance(0).saveConfig(false); startActivity(new Intent(this,LaunchActivity.class)); }
                finish();
            }
        }).setNegativeButton(text(R.string.Cancel),null).show();
    }
    @Override protected void onStart() { super.onStart(); CalamusAccountAccess.addListener(listener); }
    @Override protected void onStop() { CalamusAccountAccess.removeListener(listener); super.onStop(); }
    @Override protected void onResume() { super.onResume(); update(); }
    @Override public void onBackPressed() { moveTaskToBack(true); }
}
