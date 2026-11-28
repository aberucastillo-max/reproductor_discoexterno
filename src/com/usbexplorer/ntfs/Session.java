package com.usbexplorer.ntfs;

import android.content.Context;

import com.usbexplorer.ntfs.core.Disk;
import com.usbexplorer.ntfs.core.Groups;
import com.usbexplorer.ntfs.core.MftIndex;
import com.usbexplorer.ntfs.core.Ntfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Disco abierto actualmente. Todo lo que contiene vive solo en memoria (salvo los grupos, cifrados). */
final class Session {
    static volatile Session current;

    final UsbMassStorage usb;
    final Disk disk;
    final Ntfs fs;
    final String info;
    volatile MftIndex index;
    /** Huella del disco al montarlo (para validar y guardar la caché). */
    byte[] fingerprint;
    /** true si el índice vino de la caché de la tablet (se vigila que siga coincidiendo). */
    volatile boolean indexFromCache;
    Context app;
    /** Algún componente detectó que el índice no coincide con el disco. */
    static volatile boolean staleRequested;
    Groups groups = new Groups();
    private volatile boolean closed;

    /** Lista de reproducción / visor que se pasa entre pantallas (nunca sale de la memoria). */
    static List<Ntfs.Entry> viewerList = new ArrayList<Ntfs.Entry>();
    static int viewerStart;
    /** Posición inicial (ms) para el reproductor individual, al ampliar desde el multivídeo. */
    static long viewerStartMs;

    Session(UsbMassStorage usb, Disk disk, Ntfs fs, String info) {
        this.usb = usb; this.disk = disk; this.fs = fs; this.info = info;
    }

    boolean isClosed() { return closed; }

    private String groupsFile() {
        return "grupos-" + Long.toHexString(fs.serial);
    }

    void loadGroups(Context c) {
        try {
            groups = Groups.parse(SecureStore.load(c, groupsFile()));
        } catch (IOException e) {
            groups = new Groups();
        }
    }

    void saveGroups(Context c) throws IOException {
        SecureStore.save(c, groupsFile(), groups.serialize());
    }

    synchronized MftIndex index(MftIndex.Progress p) throws IOException {
        if (index == null) {
            index = MftIndex.build(fs, p);
            indexFromCache = false;
            if (app != null && fingerprint != null) CacheStore.saveIndex(app, fs.serial, fingerprint, index);
        }
        return index;
    }

    /** Descarta el índice (y su caché) para volver a leerlo del disco. */
    synchronized void dropIndex() {
        index = null;
        indexFromCache = false;
        if (app != null) CacheStore.invalidateIndex(app, fs.serial);
    }

    /** Expulsión segura. Devuelve true si el disco confirmó la parada. */
    static boolean eject() {
        Session s;
        synchronized (Session.class) {
            s = current;
            current = null;
        }
        if (s == null) return true;
        s.closed = true;
        AudioPlayer.get().stopAll();
        Thumbs.get().clear();
        viewerList = new ArrayList<Ntfs.Entry>();
        boolean ok;
        synchronized (s.usb) {
            ok = s.usb.stopAndEject();
        }
        s.disk.clearCache();
        return ok;
    }

    /** El disco se desconectó sin expulsar. */
    static void lost() {
        Session s;
        synchronized (Session.class) {
            s = current;
            current = null;
        }
        if (s == null) return;
        s.closed = true;
        AudioPlayer.get().stopAll();
        Thumbs.get().clear();
        viewerList = new ArrayList<Ntfs.Entry>();
        s.usb.close();
    }
}
