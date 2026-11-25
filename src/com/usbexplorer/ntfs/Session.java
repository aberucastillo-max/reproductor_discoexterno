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
    Groups groups = new Groups();
    private volatile boolean closed;

    /** Lista de reproducción / visor que se pasa entre pantallas (nunca sale de la memoria). */
    static List<Ntfs.Entry> viewerList = new ArrayList<Ntfs.Entry>();
    static int viewerStart;

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
        if (index == null) index = MftIndex.build(fs, p);
        return index;
    }

    /** Disco ya detenido que se mantiene sujeto hasta que se desconecte el cable. */
    static volatile UsbMassStorage parked;
    static volatile UsbMassStorage.EjectResult lastEject;

    /** Expulsión segura. Devuelve true si el disco confirmó la parada del motor. */
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
        UsbMassStorage.EjectResult r;
        synchronized (s.usb) {   // espera a que termine cualquier lectura en curso
            r = s.usb.stopAndPark();
        }
        lastEject = r;
        parked = s.usb;
        s.disk.clearCache();
        return r.ok();
    }

    /** El cable del disco aparcado se ha desconectado (o el usuario quiere volver a usarlo). */
    static void releaseParked() {
        UsbMassStorage p = parked;
        parked = null;
        if (p != null) p.release();
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
