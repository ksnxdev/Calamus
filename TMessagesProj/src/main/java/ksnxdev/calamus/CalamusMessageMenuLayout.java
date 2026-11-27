package ksnxdev.calamus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.graphics.Rect;
import android.view.ViewGroup;
import android.widget.TextView;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
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
    private final Runnable dismiss;
    private final ArrayList<View> touchTargets = new ArrayList<>();
    private boolean outsideGesture;
    private boolean outsideMoved;
    private float outsideX, outsideY;
    private final int touchSlop;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final ScrollView previewScroll;
    private final ScrollView actionScroll;
    private final TextView moreActions;
    private boolean foregroundOpen;
    private final int previewHeight;
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
        this.dismiss = dismiss;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setTag("calamus_ios_message_menu");
        outgoing = message.isOutOwner();
        int[] position = new int[2]; original.getLocationOnScreen(position); anchorY = position[1];
        setOnClickListener(v -> dismiss.run());
        scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(AndroidUtilities.dp(24));
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        addView(scroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(outgoing ? Gravity.RIGHT : Gravity.LEFT);
        column.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        scroll.addView(column, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (reactions != null) {
            touchTargets.add(reactions);
            reactions.setTag("calamus_ios_message_reactions");
            reactions.setMirrorX(!outgoing);
            reactions.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(20));
            column.addView(reactions, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(76) + (int) reactions.getTopOffset()));
        }
        ImageView preview = preview(original);
        previewScroll = new ScrollView(context);
        previewScroll.setTag("calamus_ios_message_preview_scroll");
        previewScroll.setVerticalScrollBarEnabled(true);
        previewScroll.setScrollbarFadingEnabled(false);
        previewScroll.setVerticalFadingEdgeEnabled(true);
        previewScroll.setFadingEdgeLength(AndroidUtilities.dp(20));
        previewScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams previewParams = previewParams(preview);
        previewHeight = previewParams.height;
        previewScroll.addView(preview, new ScrollView.LayoutParams(previewParams.width, previewHeight));
        column.addView(previewScroll, previewParams);
        touchTargets.add(previewScroll);
        if (!CherrygramMessagesConfig.INSTANCE.getMsgMenuUnifiedScroll() && CherrygramMessagesConfig.INSTANCE.getMsgMenuAutoScroll()) {
            previewScroll.post(() -> previewScroll.fullScroll(FOCUS_DOWN));
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
        touchTargets.add(actions);
        touchTargets.addAll(extras);
        for (View extra : extras) column.addView(extra, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        actionScroll = findScroll(actions);
        if (actionScroll != null) {
            actionScroll.setVerticalScrollBarEnabled(true);
            actionScroll.setScrollbarFadingEnabled(false);
            actionScroll.setVerticalFadingEdgeEnabled(true);
            actionScroll.setFadingEdgeLength(AndroidUtilities.dp(24));
            actionScroll.getViewTreeObserver().addOnScrollChangedListener(this::updateMoreActions);
        }
        moreActions = new TextView(context);
        moreActions.setTag("calamus_ios_message_more_actions");
        touchTargets.add(moreActions);
        moreActions.setText(LocaleController.getString(R.string.CalamusMoreMessageActions) + " ↓");
        moreActions.setTextSize(14);
        moreActions.setGravity(Gravity.CENTER);
        moreActions.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        moreActions.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem));
        moreActions.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24),
                Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground), Theme.getColor(Theme.key_listSelector)));
        moreActions.setOnClickListener(v -> {
            ScrollView target = scroll.canScrollVertically(1) ? scroll : actionScroll;
            if (target != null) target.smoothScrollBy(0, Math.max(AndroidUtilities.dp(96), target.getHeight() * 2 / 3));
        });
        addView(moreActions, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(48)));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateMoreActions());
        if (actions.getSwipeBack() != null) {
            foregroundOpen = actions.getSwipeBack().isForegroundOpen();
            actions.getSwipeBack().addOnSwipeBackProgressListener((layout, target, progress) -> {
                boolean open = target > 0 || progress > 0;
                if (foregroundOpen != open) {
                    foregroundOpen = open;
                    updateMoreActions();
                    requestLayout();
                }
            });
        }
    }

    private boolean isTouchTarget(float x, float y) {
        Rect bounds = new Rect();
        for (View target : touchTargets) {
            if (target.isShown() && target.getGlobalVisibleRect(bounds) && bounds.contains((int) x, (int) y)) return true;
        }
        return false;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            outsideGesture = !isTouchTarget(event.getRawX(), event.getRawY());
            outsideMoved = false;
            outsideX = event.getRawX();
            outsideY = event.getRawY();
        }
        if (outsideGesture) {
            if (action == MotionEvent.ACTION_MOVE && Math.hypot(event.getRawX() - outsideX, event.getRawY() - outsideY) > touchSlop) outsideMoved = true;
            if (action == MotionEvent.ACTION_UP) {
                outsideGesture = false;
                if (!outsideMoved) dismiss.run();
            } else if (action == MotionEvent.ACTION_CANCEL) outsideGesture = false;
            return true;
        }
        return super.dispatchTouchEvent(event);
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

    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void updateMoreActions() {
        if (moreActions == null) return;
        moreActions.setVisibility(!foregroundOpen && (scroll.canScrollVertically(1) || actionScroll != null && actionScroll.canScrollVertically(1))
                ? VISIBLE : INVISIBLE);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateMoreActions();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec), height = MeasureSpec.getSize(heightMeasureSpec);
        int topInset = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(12);
        int bottomInset = Math.max(AndroidUtilities.dp(16), AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(12));
        int limit = Math.max(1, height - topInset - bottomInset);
        previewScroll.getLayoutParams().height = Math.min(previewHeight, Math.max(AndroidUtilities.dp(48), limit / 3));
        int exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        scroll.measure(exactWidth, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        boolean overflow = column.getMeasuredHeight() > scroll.getMeasuredHeight()
                || actionScroll != null && actionScroll.getChildCount() > 0
                    && actionScroll.getChildAt(0).getMeasuredHeight() > actionScroll.getMeasuredHeight();
        int footer = !foregroundOpen && overflow ? Math.min(AndroidUtilities.dp(52), limit / 4) : 0;
        if (footer > 0) scroll.measure(exactWidth, MeasureSpec.makeMeasureSpec(limit - footer, MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        params.topMargin = Math.max(topInset, Math.min(anchorY - AndroidUtilities.dp(80), height - scroll.getMeasuredHeight() - footer - bottomInset));
        moreActions.measure(MeasureSpec.makeMeasureSpec(width - AndroidUtilities.dp(24), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(Math.max(0, footer - AndroidUtilities.dp(4)), MeasureSpec.EXACTLY));
        FrameLayout.LayoutParams hint = (FrameLayout.LayoutParams) moreActions.getLayoutParams();
        hint.topMargin = params.topMargin + scroll.getMeasuredHeight() + AndroidUtilities.dp(4);
        hint.leftMargin = outgoing ? width - moreActions.getMeasuredWidth() - AndroidUtilities.dp(12) : AndroidUtilities.dp(12);
        setMeasuredDimension(width, height);
    }
}
