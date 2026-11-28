package com.usbexplorer.ntfs;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;

import com.usbexplorer.ntfs.core.Ntfs;

import java.io.IOException;

/**
 * Descriptor de archivo "virtual" que lee directamente del disco USB, con acceso aleatorio.
 * Solo lo usa esta propia app (reproductor y miniaturas); nunca se entrega a otras apps.
 */
final class ProxyFd {
    private ProxyFd() {}

    static ParcelFileDescriptor open(Context c, final Session s, Ntfs.Entry e) throws IOException {
        final Ntfs.FileHandle h;
        try {
            h = s.fs.open(e.ref);
        } catch (IOException ex) {
            if (String.valueOf(ex.getMessage()).contains("Reindexa")) Session.staleRequested = true;
            throw ex;
        }
        StorageManager sm = c.getSystemService(StorageManager.class);
        // Un hilo propio por archivo: el vídeo, la música y las miniaturas no se bloquean entre sí.
        final HandlerThread t = new HandlerThread("usb-lectura");
        t.start();
        final int[] errors = {0};
        try {
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, new ProxyFileDescriptorCallback() {
            @Override public long onGetSize() { return h.size(); }

            @Override
            public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                if (s.isClosed()) throw new ErrnoException("onRead", OsConstants.EIO);
                try {
                    return s.fs.read(h, offset, data, 0, size);
                } catch (Throwable ex) {
                    if (errors[0]++ < 5) CrashLog.event("Error leyendo del disco: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
                    throw new ErrnoException("onRead", OsConstants.EIO);
                }
            }

            @Override public void onRelease() { t.quitSafely(); }
            }, new Handler(t.getLooper()));
        } catch (IOException | RuntimeException ex) {
            t.quitSafely();
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex.getMessage());
        }
    }

    /** Memoria usada por clips cargados enteros (todas las ventanas del multivídeo). */
    private static final java.util.concurrent.atomic.AtomicLong fullBytes = new java.util.concurrent.atomic.AtomicLong();

    static long fullBudget() {
        // como mucho un tercio de la memoria de la app para clips enteros
        return Math.min(160L << 20, Runtime.getRuntime().maxMemory() / 3);
    }

    /**
     * Igual que {@link #open} pero pensado para varios vídeos a la vez sin castigar al disco:
     *  - clips pequeños (&lt;= fullMax): se leen ENTEROS de una vez, en lectura seguida, y después el
     *    disco queda en reposo mientras se reproducen;
     *  - el resto: se leen en bloques grandes ({@code chunk} bytes) seguidos, de modo que el cabezal
     *    solo salta una vez por bloque en lugar de decenas de veces por segundo.
     */
    static ParcelFileDescriptor openBuffered(Context c, final Session s, Ntfs.Entry e, final int chunk, long fullMax)
            throws IOException {
        final Ntfs.FileHandle h;
        try {
            h = s.fs.open(e.ref);
        } catch (IOException ex) {
            if (String.valueOf(ex.getMessage()).contains("Reindexa")) Session.staleRequested = true;
            throw ex;
        }
        final long size = h.size();
        byte[] whole = null;
        if (size <= fullMax && fullBytes.get() + size <= fullBudget()) {
            whole = new byte[(int) size];
            int pos = 0;
            while (pos < whole.length) {
                int r = s.fs.read(h, pos, whole, pos, Math.min(4 << 20, whole.length - pos));
                if (r <= 0) throw new IOException("Lectura incompleta");
                pos += r;
            }
            fullBytes.addAndGet(size);
        }
        final byte[] full = whole;
        final byte[][] buf = {null};
        final long[] bufStart = {-1};
        final int[] bufLen = {0};
        StorageManager sm = c.getSystemService(StorageManager.class);
        final HandlerThread t = new HandlerThread("usb-lectura");
        t.start();
        final int[] errors = {0};
        try {
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, new ProxyFileDescriptorCallback() {
                @Override public long onGetSize() { return size; }

                @Override
                public int onRead(long offset, int len, byte[] data) throws ErrnoException {
                    if (offset >= size) return 0;
                    len = (int) Math.min(len, size - offset);
                    if (full != null) {
                        System.arraycopy(full, (int) offset, data, 0, len);
                        return len;
                    }
                    if (s.isClosed()) throw new ErrnoException("onRead", OsConstants.EIO);
                    try {
                        if (buf[0] == null || offset < bufStart[0] || offset + len > bufStart[0] + bufLen[0]) {
                            long start = offset - offset % (64 * 1024);
                            int want = (int) Math.min(Math.max(chunk, offset + len - start), size - start);
                            if (buf[0] == null || buf[0].length < want) buf[0] = new byte[want];
                            int pos = 0;
                            while (pos < want) {   // un bloque grande y seguido
                                int r = s.fs.read(h, start + pos, buf[0], pos, Math.min(1 << 20, want - pos));
                                if (r <= 0) break;
                                pos += r;
                            }
                            bufStart[0] = start;
                            bufLen[0] = pos;
                        }
                        int n = (int) Math.min(len, bufStart[0] + bufLen[0] - offset);
                        System.arraycopy(buf[0], (int) (offset - bufStart[0]), data, 0, n);
                        return n;
                    } catch (Throwable ex) {
                        if (errors[0]++ < 5) CrashLog.event("Error leyendo del disco (multivídeo): " + ex.getClass().getSimpleName());
                        throw new ErrnoException("onRead", OsConstants.EIO);
                    }
                }

                @Override public void onRelease() {
                    if (full != null) fullBytes.addAndGet(-size);
                    buf[0] = null;
                    t.quitSafely();
                }
            }, new Handler(t.getLooper()));
        } catch (IOException | RuntimeException ex) {
            if (full != null) fullBytes.addAndGet(-size);
            t.quitSafely();
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex.getMessage());
        }
    }

    /** Lee un archivo completo (o sus primeros {@code limit} bytes) a memoria. */
    static byte[] readAll(Session s, Ntfs.Entry e, long limit) throws IOException {
        Ntfs.FileHandle h;
        try {
            h = s.fs.open(e.ref);
        } catch (IOException ex) {
            if (String.valueOf(ex.getMessage()).contains("Reindexa")) Session.staleRequested = true;
            throw ex;
        }
        long n = Math.min(h.size(), limit);
        if (n > Integer.MAX_VALUE - 16) throw new IOException("Archivo demasiado grande");
        byte[] b = new byte[(int) n];
        int pos = 0;
        while (pos < b.length) {
            int r = s.fs.read(h, pos, b, pos, Math.min(1 << 20, b.length - pos));
            if (r <= 0) break;
            pos += r;
        }
        return b;
    }
}
