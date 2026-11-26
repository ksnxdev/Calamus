package ksnxdev.calamus.extera.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.os.SystemClock;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

public final class ExteraWarningDialog extends AlertDialog {
    public static final long LOCK_DURATION_MS = 5000;
    private final boolean timed;
    private final Runnable accepted;
    private long unlockAt;
    private boolean started;
    private boolean forced;
    private boolean completed;
    private final Runnable countdown = this::updateCountdown;

    public ExteraWarningDialog(Context context, Theme.ResourcesProvider provider, boolean timed, Runnable accepted) {
        super(context, 0, provider);
        this.timed = timed;
        this.accepted = accepted;
        setTitle(LocaleController.getString(R.string.CalamusExteraWarningTitle));
        setMessage(LocaleController.getString(R.string.CalamusExteraWarning));
        setPositiveButton(LocaleController.getString(R.string.CalamusExteraUnderstand), (dialog, which) -> dismiss());
        setCancelable(!timed);
        setCanceledOnTouchOutside(!timed);
    }

    @Override public void show() {
        super.show();
        if (!started) {
            started = true;
            unlockAt = SystemClock.elapsedRealtime() + (timed ? LOCK_DURATION_MS : 0);
        }
        updateCountdown();
    }

    public boolean isLocked() {
        return timed && (!started || SystemClock.elapsedRealtime() < unlockAt);
    }

    private void updateCountdown() {
        if (!isShowing()) return;
        long remaining = Math.max(0, unlockAt - SystemClock.elapsedRealtime());
        TextView button = (TextView) getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            String text = LocaleController.getString(R.string.CalamusExteraUnderstand);
            button.setText(remaining > 0 ? text + " (" + ((remaining + 999) / 1000) + ")" : text);
            button.setEnabled(remaining == 0);
            button.setAlpha(remaining == 0 ? 1f : 0.5f);
        }
        if (remaining > 0) AndroidUtilities.runOnUIThread(countdown, Math.min(remaining, 200));
        else {
            setCancelable(true);
            setCanceledOnTouchOutside(true);
        }
    }

    @Override public void cancel() {
        if (!isLocked()) super.cancel();
    }

    @Override public void dismiss() {
        if (!forced && isLocked()) return;
        AndroidUtilities.cancelRunOnUIThread(countdown);
        super.dismiss();
        if (!forced && !completed) {
            completed = true;
            if (accepted != null) accepted.run();
        }
    }

    public void dismissForDestroy() {
        forced = true;
        dismiss();
    }
}
