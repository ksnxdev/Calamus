package uz.ksnxdev.calamus;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UItem;
import uz.unnarsx.cherrygram.core.ui.MD3ListAdapter;

public final class MaterialCardDecoration extends RecyclerView.ItemDecoration {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path path = new Path();

    public boolean isCard(View view, RecyclerListView parent) {
        if (!CalamusMaterial.isEnabled()) return false;
        if (parent.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager grid && grid.getSpanCount() > 1) return false;
        if (view instanceof DialogCell) return true;
        if (!(view.getLayoutParams() instanceof RecyclerView.LayoutParams)) return false;
        RecyclerView.ViewHolder holder = parent.getChildViewHolder(view);
        if (holder == null || holder.getAdapterPosition() == RecyclerView.NO_POSITION) return false;
        if (parent.getAdapter() instanceof MD3ListAdapter adapter) {
            return adapter.isMaterialCardPosition(holder.getAdapterPosition());
        }
        if (parent.getAdapter() instanceof UniversalAdapter adapter) {
            UItem item = adapter.getItem(holder.getAdapterPosition());
            if (item == null || item.transparent || item.viewType == UniversalAdapter.VIEW_TYPE_SPACE_CG) return false;
        }
        return parent.hasMaterialCardRows() && !(view instanceof HeaderCell)
                && holder.getItemViewType() != UniversalAdapter.VIEW_TYPE_HEADER
                && !UniversalAdapter.isShadow(holder.getItemViewType());
    }

    @Override
    public void getItemOffsets(Rect out, View view, RecyclerView parent, RecyclerView.State state) {
        out.setEmpty();
        if (!(parent instanceof RecyclerListView list) || !isCard(view, list)) return;
        int horizontal = parent.getAdapter() instanceof MD3ListAdapter ? 0 : AndroidUtilities.dp(16);
        out.set(horizontal, AndroidUtilities.dp(2), horizontal, AndroidUtilities.dp(2));
    }

    public int clip(Canvas canvas, View child) {
        return clip(canvas, child, true);
    }

    public int clipSelector(Canvas canvas, View child) {
        return clip(canvas, child, false);
    }

    private int clip(Canvas canvas, View child, boolean fill) {
        int save = canvas.save();
        float x = fill ? child.getX() : child.getLeft();
        float y = fill ? child.getY() : child.getTop();
        bounds.set(x, y, x + child.getWidth(), y + child.getHeight());
        path.rewind();
        path.addRoundRect(bounds, AndroidUtilities.dp(16), AndroidUtilities.dp(16), Path.Direction.CW);
        canvas.clipPath(path);
        if (fill) {
            paint.setColor(Theme.getColor(Theme.key_dialogBackground));
            paint.setAlpha(Math.round(255 * child.getAlpha()));
            canvas.drawRoundRect(bounds, AndroidUtilities.dp(16), AndroidUtilities.dp(16), paint);
        }
        return save;
    }
}
