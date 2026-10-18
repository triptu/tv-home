package com.gathin.tvhome.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * One artwork as it hangs: a soft drop shadow, a thin dark walnut moulding and, for paintings, a cream mat.
 * The bitmap arrives decoded at exactly its on-screen size, so it is drawn 1:1. The view is sized to the
 * frame plus {@link Geometry#shadow} on every side, so fading it only needs a small layer.
 */
final class FramedArtView extends View {

    private static final int FRAME = 0xFF1E1510;
    private static final int FRAME_LIP = 0xFF4A3628;
    private static final int SPACE_FRAME = 0xFF120D0A;
    private static final int MAT = 0xFFF1EAD2;
    private static final int MAT_BEVEL = 0xFFFAF6EA;

    /** Frame, mat and shadow sizes in pixels, scaled from the wall height. */
    static final class Geometry {
        final int frame, mat, shadow;
        final float shadowBlur, shadowDrop;

        Geometry(int wallHeight, boolean matted) {
            frame = Math.max(4, Math.round(wallHeight * 0.011f));
            mat = matted ? Math.round(wallHeight * 0.034f) : 0;
            shadowBlur = wallHeight * 0.03f;
            shadowDrop = wallHeight * 0.014f;
            shadow = Math.round(shadowBlur * 1.6f + shadowDrop);
        }

        int border() {
            return frame + mat;
        }
    }

    private final Bitmap bitmap;
    private final Geometry geometry;
    private final boolean matted;
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();

    FramedArtView(Context ctx, Bitmap bitmap, Geometry geometry, boolean matted) {
        super(ctx);
        this.bitmap = bitmap;
        this.geometry = geometry;
        this.matted = matted;
        shadowPaint.setColor(matted ? FRAME : SPACE_FRAME);
        shadowPaint.setShadowLayer(geometry.shadowBlur, 0, geometry.shadowDrop, 0xA6000000);
    }

    Bitmap bitmap() {
        return bitmap;
    }

    int viewWidth() {
        return bitmap.getWidth() + 2 * (geometry.border() + geometry.shadow);
    }

    int viewHeight() {
        return bitmap.getHeight() + 2 * (geometry.border() + geometry.shadow);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap.isRecycled()) return;
        int s = geometry.shadow;
        rect.set(s, s, getWidth() - s, getHeight() - s);
        canvas.drawRect(rect, shadowPaint);

        // A one-pixel lighter lip on the inside edge of the moulding catches the light.
        float lip = Math.max(1f, geometry.frame / 4f);
        inset(geometry.frame - lip);
        fill.setColor(FRAME_LIP);
        canvas.drawRect(rect, fill);

        int border = geometry.border();
        if (matted) {
            inset(lip);
            fill.setColor(MAT);
            canvas.drawRect(rect, fill);
            inset(geometry.mat - 1);
            fill.setColor(MAT_BEVEL);
            canvas.drawRect(rect, fill);
        }
        canvas.drawBitmap(bitmap, s + border, s + border, bitmapPaint);
    }

    private void inset(float by) {
        rect.inset(by, by);
    }
}
