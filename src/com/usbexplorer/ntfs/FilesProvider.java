package com.usbexplorer.ntfs;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;

import com.usbexplorer.ntfs.core.Ntfs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Entrega archivos del disco NTFS a otras apps (galería, reproductor de vídeo, visor PDF...)
 * leyendo directamente del USB, con acceso aleatorio (se puede avanzar en un vídeo).
 * URI: content://com.usbexplorer.ntfs.files/<ref>/<tamaño>/<nombre>
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "com.usbexplorer.ntfs.files";
    private static HandlerThread thread;

    static Uri uriFor(Ntfs.Entry e) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(Long.toString(e.ref)).appendPath(Long.toString(e.size))
                .appendPath(e.name).build();
    }

    static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    private static synchronized Handler handler() {
        if (thread == null) {
            thread = new HandlerThread("usb-lectura");
            thread.start();
        }
        return new Handler(thread.getLooper());
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        List<String> p = uri.getPathSegments();
        return p.size() >= 3 ? mimeFor(p.get(2)) : null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        List<String> p = uri.getPathSegments();
        if (p.size() < 3) return null;
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = p.get(2);
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = Long.parseLong(p.get(1));
        }
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Solo lectura");
        final Session s = Session.current;
        if (s == null) throw new FileNotFoundException("El disco no está conectado");
        List<String> p = uri.getPathSegments();
        if (p.size() < 3) throw new FileNotFoundException();
        try {
            final Ntfs.FileHandle h = s.fs.open(Long.parseLong(p.get(0)));
            StorageManager sm = getContext().getSystemService(StorageManager.class);
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    new ProxyFileDescriptorCallback() {
                        @Override public long onGetSize() { return h.size(); }

                        @Override
                        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                            try {
                                return s.fs.read(h, offset, data, 0, size);
                            } catch (IOException e) {
                                throw new ErrnoException("onRead", OsConstants.EIO);
                            }
                        }

                        @Override public void onRelease() { }
                    }, handler());
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        } catch (NumberFormatException e) {
            throw new FileNotFoundException();
        }
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
