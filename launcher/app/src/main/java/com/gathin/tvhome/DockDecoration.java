package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        band.setColor(0x99141416);   // 60%: the photo behind shows through a little
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);
    }

    /** Space under the dock row, in dp; negative pulls the first grid row closer to it. */
    static final int DOCK_BOTTOM_DP = -14;

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            // Negative: the dock shows no name labels (the top shelf names the app), so pull the next row
            // up into that unused space; its tops then peek out under the dock.
            outRect.bottom = (int) (DOCK_BOTTOM_DP * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        float cardW = 0, cardH = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            cardW = Math.max(cardW, card.getWidth());
            cardH = Math.max(cardH, card.getHeight());
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        // Room for the focused tile as magnified (AppAdapter.FOCUS_SCALE) plus the glass around it (6dp, as in
        // MainActivity.glassTo) and a margin, so the highlight on an end tile stays inside the band. Fixed, not
        // following the focus, so the band doesn't breathe as focus moves.
        float margin = 6 * density + 8 * density;
        float grow = (AppAdapter.FOCUS_SCALE - 1f) / 2f;
        rect.inset(-(cardW * grow + margin), -(cardH * grow + margin));
        float radius = 30 * density;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
