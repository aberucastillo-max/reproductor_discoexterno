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
        final Ntfs.FileHandle h = s.fs.open(e.ref);
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

    /** Lee un archivo completo (o sus primeros {@code limit} bytes) a memoria. */
    static byte[] readAll(Session s, Ntfs.Entry e, long limit) throws IOException {
        Ntfs.FileHandle h = s.fs.open(e.ref);
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
