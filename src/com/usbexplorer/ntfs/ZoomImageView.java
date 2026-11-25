package com.usbexplorer.ntfs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Visor con zoom (pellizcar / doble toque) y desplazamiento. Al acercarse, decodifica en alta
 * resolución solo la zona visible, para ver la foto con todo detalle sin agotar la memoria.
 */
final class ZoomImageView extends View {
    interface Listener {
        void onSwipe(int direction);   // +1 siguiente, -1 anterior
        void onTap();
    }

    private static final int BASE_MAX = 2048;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();       // coordenadas de la imagen original -> pantalla
    private final Matrix tmp = new Matrix();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private Listener listener;

    private Bitmap base;          // imagen completa reducida
    private int baseSample = 1;
    private int imgW, imgH;       // tamaño original (sin rotar)
    private int rotation;
    private BitmapRegionDecoder region;
    private Bitmap detail;
    private Rect detailRect;
    private int detailToken;
    private float fitScale = 1;
    private Thread detailThread;

    ZoomImageView(Context c) {
        super(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { scheduleDetail(); }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (isZoomed()) { matrix.postTranslate(-dx, -dy); clamp(); invalidate(); }
                return true;
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (!isZoomed() && listener != null && a != null && Math.abs(vx) > Math.abs(vy) * 1.5f
                        && Math.abs(b.getX() - a.getX()) > getWidth() / 8f) {
                    listener.onSwipe(vx < 0 ? 1 : -1);
                } else {
                    scheduleDetail();
                }
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (isZoomed()) fit(); else zoomBy(3f, e.getX(), e.getY());
                scheduleDetail();
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    /** Prepara la imagen a partir de los bytes del archivo. Llamar desde un hilo de fondo. */
    static Object[] decode(byte[] data, int rotation) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        int w = o.outWidth, h = o.outHeight;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.max(w, h) / o.inSampleSize > BASE_MAX) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) return null;
        BitmapRegionDecoder rd = null;
        if (o.inSampleSize > 1) {
            try { rd = BitmapRegionDecoder.newInstance(data, 0, data.length, false); } catch (Exception ignored) { }
        }
        return new Object[]{b, o.inSampleSize, w, h, rotation, rd};
    }

    void setImage(Object[] d) {
        recycle();
        if (d == null) { invalidate(); return; }
        base = (Bitmap) d[0];
        baseSample = (Integer) d[1];
        imgW = (Integer) d[2];
        imgH = (Integer) d[3];
        rotation = (Integer) d[4];
        region = (BitmapRegionDecoder) d[5];
        if (getWidth() > 0) fit();
        invalidate();
    }

    void recycle() {
        detailToken++;
        if (region != null) { region.recycle(); region = null; }
        detail = null;
        base = null;
    }

    boolean isZoomed() { return currentScale() > fitScale * 1.05f; }

    private float currentScale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return (float) Math.hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]);
    }

    private void fit() {
        if (base == null || getWidth() == 0) return;
        boolean swap = rotation == 90 || rotation == 270;
        float w = swap ? imgH : imgW, h = swap ? imgW : imgH;
        fitScale = Math.min(getWidth() / w, getHeight() / h);
        matrix.reset();
        matrix.postTranslate(-imgW / 2f, -imgH / 2f);
        matrix.postRotate(rotation);
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate(getWidth() / 2f, getHeight() / 2f);
        detail = null;
        invalidate();
    }

    private void zoomBy(float f, float fx, float fy) {
        float s = currentScale();
        float max = Math.max(fitScale * 12, 4f);
        if (s * f < fitScale) f = fitScale / s;
        if (s * f > max) f = max / s;
        matrix.postScale(f, f, fx, fy);
        clamp();
        invalidate();
    }

    private void clamp() {
        RectF r = new RectF(0, 0, imgW, imgH);
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = getWidth() / 2f - r.centerX();
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = getHeight() / 2f - r.centerY();
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    private final Runnable detailTask = new Runnable() {
        @Override public void run() { loadDetail(); }
    };

    private void scheduleDetail() {
        ui.removeCallbacks(detailTask);
        ui.postDelayed(detailTask, 200);
    }

    private void loadDetail() {
        if (region == null || base == null) return;
        float s = currentScale();
        // ¿la imagen reducida ya basta para esta ampliación?
        if (s * baseSample <= 1.2f) { if (detail != null) { detail = null; invalidate(); } return; }
        Matrix inv = new Matrix();
        if (!matrix.invert(inv)) return;
        RectF vis = new RectF(0, 0, getWidth(), getHeight());
        inv.mapRect(vis);
        final Rect r = new Rect((int) Math.max(0, vis.left), (int) Math.max(0, vis.top),
                (int) Math.min(imgW, vis.right), (int) Math.min(imgH, vis.bottom));
        if (r.isEmpty()) return;
        int sample = 1;
        while (1f / (sample * 2) >= s) sample *= 2;
        final int fs = sample;
        final int token = ++detailToken;
        final BitmapRegionDecoder rd = region;
        detailThread = new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = fs;
                    synchronized (rd) { if (!rd.isRecycled()) b = rd.decodeRegion(r, o); }
                } catch (Throwable ignored) {
                }
                final Bitmap fb = b;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (token != detailToken || fb == null) return;
                        detail = fb;
                        detailRect = r;
                        invalidate();
                    }
                });
            }
        }, "detalle");
        detailThread.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP && isZoomed()) scheduleDetail();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (base == null) return;
        tmp.set(matrix);
        tmp.preScale(baseSample, baseSample);
        c.drawBitmap(base, tmp, paint);
        if (detail != null && detailRect != null) {
            tmp.set(matrix);
            tmp.preTranslate(detailRect.left, detailRect.top);
            tmp.preScale((float) detailRect.width() / detail.getWidth(), (float) detailRect.height() / detail.getHeight());
            c.drawBitmap(detail, tmp, paint);
        }
    }
}
