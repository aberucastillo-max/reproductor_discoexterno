package com.usbexplorer.ntfs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Barra vertical táctil (volumen / brillo) para los controles flotantes del reproductor. */
final class VerticalBar extends View {
    interface Listener { void onChange(float value, boolean fromUser); }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float value;
    private Listener listener;
    private boolean dragging;

    VerticalBar(Context c) {
        super(c);
        track.setColor(0x55FFFFFF);
        fill.setColor(Ui.ACCENT);
        thumb.setColor(0xFFFFFFFF);
        int p = Ui.dp(c, 10);
        setPadding(p, p, p, p);
    }

    void setListener(Listener l) { listener = l; }
    boolean isDragging() { return dragging; }
    float value() { return value; }

    void setValue(float v) {
        v = Math.max(0, Math.min(1, v));
        if (Math.abs(v - value) < 0.001f) return;
        value = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = Ui.dp(getContext(), 6), cx = getWidth() / 2f;
        float top = getPaddingTop(), bottom = getHeight() - getPaddingBottom();
        r.set(cx - w / 2, top, cx + w / 2, bottom);
        c.drawRoundRect(r, w, w, track);
        float y = bottom - (bottom - top) * value;
        r.set(cx - w / 2, y, cx + w / 2, bottom);
        c.drawRoundRect(r, w, w, fill);
        c.drawCircle(cx, y, Ui.dp(getContext(), dragging ? 10 : 8), thumb);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            float top = getPaddingTop(), bottom = getHeight() - getPaddingBottom();
            setValue((bottom - e.getY()) / (bottom - top));
            invalidate();
            if (listener != null) listener.onChange(value, true);
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            invalidate();
            if (listener != null) listener.onChange(value, true);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
