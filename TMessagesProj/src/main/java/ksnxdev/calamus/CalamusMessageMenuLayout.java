package ksnxdev.calamus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.ReactionsContainerLayout;

import java.util.ArrayList;

import uz.unnarsx.cherrygram.core.configs.CherrygramMessagesConfig;

public final class CalamusMessageMenuLayout extends FrameLayout {
    private final ScrollView scroll;
    private final LinearLayout column;
    private final int anchorY;
    private final boolean outgoing;

    public static boolean enabled(MessageObject message, View view) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !AndroidUtilities.isTablet()
                && CherrygramMessagesConfig.INSTANCE.getBlurMessageMenuBackground()
                && message != null && !message.isSecretMedia()
                && message.getGroupId() == 0 && view instanceof ChatMessageCell
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    public CalamusMessageMenuLayout(Context context, View original, MessageObject message,
            ReactionsContainerLayout reactions, ActionBarPopupWindow.ActionBarPopupWindowLayout actions,
            ArrayList<View> extras, Runnable dismiss) {
        super(context);
        setTag("calamus_ios_message_menu");
        outgoing = message.isOutOwner();
        int[] position = new int[2]; original.getLocationOnScreen(position); anchorY = position[1];
        setOnClickListener(v -> dismiss.run());
        scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        addView(scroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(outgoing ? Gravity.RIGHT : Gravity.LEFT);
        column.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        scroll.addView(column, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (reactions != null) {
            reactions.setTag("calamus_ios_message_reactions");
            reactions.setMirrorX(!outgoing);
            reactions.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(20));
            column.addView(reactions, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(76) + (int) reactions.getTopOffset()));
        }
        ImageView preview = preview(original);
        if (CherrygramMessagesConfig.INSTANCE.getMsgMenuUnifiedScroll()) {
            column.addView(preview, previewParams(preview));
        } else {
            ScrollView messageScroll = new ScrollView(context);
            messageScroll.setVerticalScrollBarEnabled(false);
            messageScroll.addView(preview, new ScrollView.LayoutParams(preview.getDrawable().getIntrinsicWidth(), preview.getDrawable().getIntrinsicHeight()));
            LinearLayout.LayoutParams params = previewParams(preview);
            params.height = Math.min(params.height, AndroidUtilities.displaySize.y / 3);
            column.addView(messageScroll, params);
            if (CherrygramMessagesConfig.INSTANCE.getMsgMenuAutoScroll()) messageScroll.post(() -> messageScroll.fullScroll(FOCUS_DOWN));
        }
        actions.setTag("calamus_ios_message_actions");
        actions.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground)));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        actionParams.gravity = outgoing ? Gravity.RIGHT : Gravity.LEFT;
        actionParams.topMargin = AndroidUtilities.dp(6);
        if (!CherrygramMessagesConfig.INSTANCE.getMsgMenuUnifiedScroll() && CherrygramMessagesConfig.INSTANCE.getMsgMenuFixedHeight()) {
            actions.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.displaySize.x - AndroidUtilities.dp(24), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            actionParams.height = Math.min(actions.getMeasuredHeight(), AndroidUtilities.displaySize.y / 2);
        }
        column.addView(actions, actionParams);
        for (View extra : extras) column.addView(extra, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout.LayoutParams previewParams(ImageView view) {
        int width = view.getDrawable().getIntrinsicWidth(), height = view.getDrawable().getIntrinsicHeight();
        int maxWidth = AndroidUtilities.displaySize.x - AndroidUtilities.dp(32);
        if (width > maxWidth) { height = Math.max(1, (int) ((long) height * maxWidth / width)); width = maxWidth; }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.gravity = outgoing ? Gravity.RIGHT : Gravity.LEFT;
        return params;
    }

    private ImageView preview(View original) {
        ChatMessageCell cell = (ChatMessageCell) original;
        int left = Math.max(0, cell.getBackgroundDrawableLeft() - AndroidUtilities.dp(3));
        int right = Math.min(original.getWidth(), cell.getBackgroundDrawableRight() + AndroidUtilities.dp(3));
        int top = Math.max(0, cell.getBackgroundDrawableTop());
        int bottom = Math.min(original.getHeight(), cell.getBackgroundDrawableBottom() + AndroidUtilities.dp(3));
        int width = Math.max(1, right - left), height = Math.max(1, bottom - top);
        float scale = Math.min(1f, (float) Math.sqrt(4_000_000d / ((long) width * height)));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);canvas.scale(scale, scale);canvas.translate(-left, -top);
        cell.setFullyDraw(true);
        try {
            if (cell.drawBackgroundInParent()) cell.drawBackgroundInternal(canvas, true);
            original.draw(canvas);
        } finally { cell.setFullyDraw(false); }
        ImageView image = new ImageView(getContext());
        image.setTag("calamus_ios_message_preview");
        image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(cell.getMessageObject().messageText);
        return image;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec), height = MeasureSpec.getSize(heightMeasureSpec);
        int topInset = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(12);
        int bottomInset = Math.max(AndroidUtilities.dp(16), AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(12));
        int limit = Math.max(1, height - topInset - bottomInset);
        scroll.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        params.topMargin = Math.max(topInset, Math.min(anchorY - AndroidUtilities.dp(80), height - scroll.getMeasuredHeight() - bottomInset));
        setMeasuredDimension(width, height);
    }
}
