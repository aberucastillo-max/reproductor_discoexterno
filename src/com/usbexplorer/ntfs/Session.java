package com.usbexplorer.ntfs;

import com.usbexplorer.ntfs.core.Disk;
import com.usbexplorer.ntfs.core.Ntfs;

/** Disco abierto actualmente (compartido entre la pantalla y el proveedor de archivos). */
final class Session {
    static volatile Session current;

    final UsbMassStorage usb;
    final Disk disk;
    final Ntfs fs;

    Session(UsbMassStorage usb, Disk disk, Ntfs fs) {
        this.usb = usb; this.disk = disk; this.fs = fs;
    }

    static synchronized void close() {
        Session s = current;
        current = null;
        if (s != null) s.usb.close();
    }
}
