package com.usbexplorer.ntfs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.widget.ImageView;

import com.usbexplorer.ntfs.core.MediaType;
import com.usbexplorer.ntfs.core.Ntfs;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Miniaturas de fotos y vídeos. Solo en memoria (nunca se guardan en la tablet) y se borran al
 * expulsar el disco.
 */
final class Thumbs {
    static final int SIZE = 360;
    private static final Thumbs INSTANCE = new Thumbs();
    static Thumbs get() { return INSTANCE; }

    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(64 * 1024 * 1024) {
        @Override protected int sizeOf(Long k, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<Long> failed = new HashSet<Long>();
    private final Deque<Object[]> queue = new ArrayDeque<Object[]>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Thread worker;
    private Context app;
    private boolean paused;

    private Thumbs() {}

    /** Durante la reproducción de vídeo no se generan miniaturas (el disco es para el vídeo). */
    synchronized void setPaused(boolean p) {
        paused = p;
        notifyAll();
    }

    static boolean supports(Ntfs.Entry e) {
        if (e.dir) return false;
        MediaType t = MediaType.of(e.name);
        if (t == MediaType.IMAGE) return true;
        if (t != MediaType.VIDEO) return false;
        String x = MediaType.ext(e.name);
        return x.equals("mp4") || x.equals("m4v") || x.equals("mov") || x.equals("3gp") || x.equals("mkv") || x.equals("webm");
    }

    /** Carga la miniatura de {@code e} en {@code v} (si la vista sigue mostrando ese archivo). */
    void load(Context c, final Ntfs.Entry e, final ImageView v) {
        v.setTag(e.ref);
        Bitmap b = cache.get(e.ref);
        if (b != null) { v.setImageBitmap(b); return; }
        v.setImageDrawable(null);
        if (!supports(e)) return;
        synchronized (this) {
            if (failed.contains(e.ref)) return;
            app = c.getApplicationContext();
            for (Object[] q : queue) if (q[1] == v) { queue.remove(q); break; }
            queue.addFirst(new Object[]{e, v});
            if (worker == null) {
                worker = new Thread(new Runnable() { @Override public void run() { loop(); } }, "miniaturas");
                worker.setPriority(Thread.MIN_PRIORITY);
                worker.start();
            }
            notifyAll();
        }
    }

    Bitmap cached(long ref) { return cache.get(ref); }

    synchronized void clear() {
        queue.clear();
        failed.clear();
        cache.evictAll();
    }

    private void loop() {
        while (true) {
            Object[] job;
            synchronized (this) {
                while (queue.isEmpty() || paused) {
                    try { wait(); } catch (InterruptedException e) { return; }
                }
                job = queue.pollFirst();
            }
            final Ntfs.Entry e = (Ntfs.Entry) job[0];
            final ImageView v = (ImageView) job[1];
            if (!Long.valueOf(e.ref).equals(v.getTag())) continue;   // la vista ya muestra otro archivo
            Bitmap b = cache.get(e.ref);
            if (b == null) {
                Session s = Session.current;
                if (s == null) continue;
                try {
                    b = MediaType.of(e.name) == MediaType.IMAGE ? image(s, e) : video(s, e);
                } catch (Throwable t) {
                    b = null;
                }
                if (b == null) { synchronized (this) { failed.add(e.ref); } continue; }
                cache.put(e.ref, b);
            }
            final Bitmap fb = b;
            ui.post(new Runnable() {
                @Override public void run() {
                    if (Long.valueOf(e.ref).equals(v.getTag())) v.setImageBitmap(fb);
                }
            });
        }
    }

    static Bitmap image(Session s, Ntfs.Entry e) throws Exception {
        // 1) miniatura EXIF incrustada (solo hay que leer el principio del archivo)
        byte[] head = ProxyFd.readAll(s, e, 256 * 1024);
        int rot = 0;
        try {
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(head));
            rot = rotation(ex);
            if (ex.hasThumbnail()) {
                Bitmap t = ex.getThumbnailBitmap();
                if (t != null && Math.max(t.getWidth(), t.getHeight()) >= 150) return rotate(t, rot);
            }
        } catch (Exception ignored) {
        }
        // 2) decodificar la imagen completa reducida
        byte[] all = e.size <= head.length ? head : ProxyFd.readAll(s, e, 60L * 1024 * 1024);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(all, 0, all.length, o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= SIZE) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeByteArray(all, 0, all.length, o);
        if (b == null) return null;
        return rotate(scale(b, SIZE), rot);
    }

    private Bitmap video(Session s, Ntfs.Entry e) throws Exception {
        ParcelFileDescriptor pfd = ProxyFd.open(app, s, e);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(pfd.getFileDescriptor());
            Bitmap b = r.getScaledFrameAtTime(3_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE);
            return b;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
            pfd.close();
        }
    }

    /** Grados de giro según la etiqueta EXIF de orientación. */
    static int rotation(ExifInterface ex) {
        switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            case ExifInterface.ORIENTATION_ROTATE_90: return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    static Bitmap scale(Bitmap b, int max) {
        int m = Math.max(b.getWidth(), b.getHeight());
        if (m <= max) return b;
        float f = (float) max / m;
        Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * f)), Math.max(1, (int) (b.getHeight() * f)), true);
        if (s != b) b.recycle();
        return s;
    }

    static Bitmap rotate(Bitmap b, int deg) {
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }
}
