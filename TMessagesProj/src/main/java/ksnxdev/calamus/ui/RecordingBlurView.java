package ksnxdev.calamus.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import org.telegram.messenger.Utilities;

public final class RecordingBlurView extends View {
    private Bitmap bitmap;
    private boolean capturing;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds = new Rect();
    public RecordingBlurView(Context context) { super(context); }
    public void capture(View source, View camera) {
        clear();
        if (source.getWidth() <= 0 || source.getHeight() <= 0) return;
        Bitmap next = Bitmap.createBitmap(Math.max(1, source.getWidth() / 8), Math.max(1, source.getHeight() / 8), Bitmap.Config.ARGB_8888);
        float alpha = camera == null ? 1 : camera.getAlpha();
        capturing = true;
        try {
            if (camera != null) camera.setAlpha(0);
            Canvas canvas = new Canvas(next);
            canvas.scale(next.getWidth() / (float) source.getWidth(), next.getHeight() / (float) source.getHeight());
            source.draw(canvas);
            Utilities.stackBlurBitmap(next, 5);
            bitmap = next;
        } finally {
            capturing = false;
            if (camera != null) camera.setAlpha(alpha);
            if (bitmap != next) next.recycle();
        }
        invalidate();
    }
    public void clear() {
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        if (capturing || bitmap == null) return;
        bounds.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, null, bounds, paint);
        canvas.drawColor(0x22000000);
    }
    @Override protected void onDetachedFromWindow() { clear(); super.onDetachedFromWindow(); }
}
