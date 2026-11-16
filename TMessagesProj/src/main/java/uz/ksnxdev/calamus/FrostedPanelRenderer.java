package uz.ksnxdev.calamus;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;

@RequiresApi(Build.VERSION_CODES.S)
public final class FrostedPanelRenderer {
    private final RenderNode node = new RenderNode("CalamusFrostedPanel");

    public FrostedPanelRenderer() {
        float radius = AndroidUtilities.dp(18);
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    public void draw(Canvas canvas, BlurredBackgroundSource source, Rect bounds, float offsetX, float offsetY) {
        int padding = AndroidUtilities.dp(36);
        int width = bounds.width() + 2 * padding;
        int height = bounds.height() + 2 * padding;
        float left = bounds.left + offsetX - padding;
        float top = bounds.top + offsetY - padding;
        node.setPosition(bounds.left - padding, bounds.top - padding,
                bounds.right + padding, bounds.bottom + padding);
        Canvas recording = node.beginRecording(width, height);
        recording.translate(-left, -top);
        source.draw(recording, left, top, left + width, top + height);
        node.endRecording();
        canvas.drawRenderNode(node);
    }
}
