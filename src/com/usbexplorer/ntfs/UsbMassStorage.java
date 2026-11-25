package com.usbexplorer.ntfs;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.usbexplorer.ntfs.core.BlockDevice;

import java.io.Closeable;
import java.io.IOException;

/**
 * Disco USB de almacenamiento masivo (protocolo Bulk-Only + comandos SCSI) usando
 * la API USB Host de Android. Sin root. Solo envía comandos de LECTURA.
 */
public final class UsbMassStorage implements BlockDevice, Closeable {
    private static final int TIMEOUT = 10000;
    private static final int MAX_XFER = 128 * 1024;

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint in, out;
    private final int chunk;
    private final byte[] cbw = new byte[31];
    private final byte[] csw = new byte[13];
    private int tag = 1;
    private int lastTransferred;
    private int lun;
    private int blockSize;
    private long blockCount;
    public String vendor = "", product = "";
    public final UsbDevice device;

    private UsbMassStorage(UsbDevice d, UsbDeviceConnection c, UsbInterface f, UsbEndpoint in, UsbEndpoint out) {
        this.device = d; this.conn = c; this.intf = f; this.in = in; this.out = out;
        this.chunk = Build.VERSION.SDK_INT >= 28 ? 65536 : 16384;
    }

    /** Devuelve la interfaz de almacenamiento masivo Bulk-Only, o null. */
    public static UsbInterface findInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
                    && f.getInterfaceProtocol() == 0x50
                    && endpoint(f, UsbConstants.USB_DIR_IN) != null
                    && endpoint(f, UsbConstants.USB_DIR_OUT) != null) {
                return f;
            }
        }
        return null;
    }

    private static UsbEndpoint endpoint(UsbInterface f, int dir) {
        for (int i = 0; i < f.getEndpointCount(); i++) {
            UsbEndpoint e = f.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == dir) return e;
        }
        return null;
    }

    public static UsbMassStorage open(UsbManager m, UsbDevice d) throws IOException {
        UsbInterface f = findInterface(d);
        if (f == null) throw new IOException("El dispositivo no es un disco USB compatible");
        UsbDeviceConnection c = m.openDevice(d);
        if (c == null) throw new IOException("Android no permitió abrir el dispositivo USB");
        if (!c.claimInterface(f, true)) {
            c.close();
            throw new IOException("No se pudo tomar el control del disco USB");
        }
        UsbMassStorage s = new UsbMassStorage(d, c, f,
                endpoint(f, UsbConstants.USB_DIR_IN), endpoint(f, UsbConstants.USB_DIR_OUT));
        try {
            s.init();
        } catch (IOException e) {
            s.close();
            throw e;
        }
        return s;
    }

    private void init() throws IOException {
        int maxLun = getMaxLun();
        IOException last = null;
        for (lun = 0; lun <= maxLun; lun++) {
            try {
                byte[] inq = new byte[36];
                int st = command(new byte[]{0x12, 0, 0, 0, 36, 0}, 6, inq, 0, 36, true);
                if (st != 0) continue;
                int type = inq[0] & 0x1F;
                if (type != 0x00) continue;   // solo unidades de disco (no CD virtual, etc.)
                vendor = new String(inq, 8, 8, "US-ASCII").trim();
                product = new String(inq, 16, 16, "US-ASCII").trim();
                waitReady();
                readCapacity();
                if (blockCount > 0 && blockSize >= 512) return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No se encontró ninguna unidad de disco en el dispositivo");
    }

    /** Un disco mecánico tarda unos segundos en arrancar: esperamos hasta ~25 s. */
    private void waitReady() throws IOException {
        for (int i = 0; i < 50; i++) {
            if (command(new byte[6], 6, null, 0, 0, true) == 0) return;
            requestSense();
            sleep(500);
        }
        throw new IOException("El disco no termina de arrancar (¿le falta energía?)");
    }

    private void readCapacity() throws IOException {
        IOException err = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                byte[] r = new byte[8];
                byte[] cb = new byte[10];
                cb[0] = 0x25;
                if (command(cb, 10, r, 0, 8, true) != 0) { requestSense(); sleep(300); continue; }
                long last = be32(r, 0);
                blockSize = (int) be32(r, 4);
                if (last == 0xFFFFFFFFL) {   // disco > 2 TB con sectores de 512: READ CAPACITY(16)
                    byte[] r16 = new byte[32];
                    byte[] c16 = new byte[16];
                    c16[0] = (byte) 0x9E; c16[1] = 0x10; c16[13] = 32;
                    if (command(c16, 16, r16, 0, 32, true) != 0) throw new IOException("READ CAPACITY(16) falló");
                    last = be32(r16, 0) << 32 | be32(r16, 4);
                    blockSize = (int) be32(r16, 8);
                }
                blockCount = last + 1;
                return;
            } catch (IOException e) {
                err = e;
            }
        }
        throw err != null ? err : new IOException("No se pudo leer la capacidad del disco");
    }

    @Override public int blockSize() { return blockSize; }
    @Override public long blockCount() { return blockCount; }

    @Override
    public synchronized void readBlocks(long lba, byte[] buf, int off, int count) throws IOException {
        int maxBlocks = Math.max(1, MAX_XFER / blockSize);
        while (count > 0) {
            int n = Math.min(count, maxBlocks);
            IOException err = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    read(lba, buf, off, n);
                    err = null;
                    break;
                } catch (IOException e) {
                    err = e;
                    sleep(200);
                }
            }
            if (err != null) throw err;
            lba += n; off += n * blockSize; count -= n;
        }
    }

    private void read(long lba, byte[] buf, int off, int n) throws IOException {
        byte[] cb = new byte[16];
        int len;
        if (lba + n > 0xFFFFFFFFL) {
            cb[0] = (byte) 0x88;
            for (int i = 0; i < 8; i++) cb[2 + i] = (byte) (lba >>> (56 - 8 * i));
            for (int i = 0; i < 4; i++) cb[10 + i] = (byte) (n >>> (24 - 8 * i));
            len = 16;
        } else {
            cb[0] = 0x28;
            for (int i = 0; i < 4; i++) cb[2 + i] = (byte) (lba >>> (24 - 8 * i));
            cb[7] = (byte) (n >>> 8); cb[8] = (byte) n;
            len = 10;
        }
        if (command(cb, len, buf, off, n * blockSize, true) != 0) {
            String s = requestSense();
            throw new IOException("Error de lectura en el sector " + lba + " (" + s + ")");
        }
        if (lastTransferred != n * blockSize) {
            throw new IOException("El disco devolvió menos datos de los pedidos (sector " + lba + ")");
        }
    }

    private String requestSense() {
        try {
            byte[] s = new byte[18];
            if (command(new byte[]{0x03, 0, 0, 0, 18, 0}, 6, s, 0, 18, true) == 0) {
                return String.format("sense %X/%02X/%02X", s[2] & 0x0F, s[12] & 0xFF, s[13] & 0xFF);
            }
        } catch (IOException ignored) {
        }
        return "sin datos";
    }

    /** Ejecuta un comando SCSI. Devuelve el estado CSW (0 = correcto). */
    private int command(byte[] cb, int cbLen, byte[] data, int off, int len, boolean dirIn) throws IOException {
        int t = tag++;
        java.util.Arrays.fill(cbw, (byte) 0);
        le32(cbw, 0, 0x43425355);
        le32(cbw, 4, t);
        le32(cbw, 8, len);
        cbw[12] = (byte) (dirIn ? 0x80 : 0x00);
        cbw[13] = (byte) lun;
        cbw[14] = (byte) cbLen;
        System.arraycopy(cb, 0, cbw, 15, cbLen);
        int r = conn.bulkTransfer(out, cbw, 31, TIMEOUT);
        if (r != 31) {
            resetRecovery();
            throw new IOException("Error de comunicación USB (envío " + r + ")");
        }
        int done = 0;
        while (done < len) {
            int n = Math.min(chunk, len - done);
            int rr = dirIn ? conn.bulkTransfer(in, data, off + done, n, TIMEOUT)
                           : conn.bulkTransfer(out, data, off + done, n, TIMEOUT);
            if (rr < 0) { clearHalt(dirIn ? in : out); break; }
            done += rr;
            if (rr < n) break;   // paquete corto: el dispositivo no tiene más datos
        }
        r = conn.bulkTransfer(in, csw, 13, TIMEOUT);
        if (r < 0) {
            clearHalt(in);
            r = conn.bulkTransfer(in, csw, 13, TIMEOUT);
        }
        if (r != 13 || le32(csw, 0) != 0x53425355 || le32(csw, 4) != t) {
            resetRecovery();
            throw new IOException("Respuesta USB no válida del disco");
        }
        int status = csw[12] & 0xFF;
        if (status == 2) {
            resetRecovery();
            throw new IOException("Error de fase USB");
        }
        lastTransferred = done;
        return status;
    }

    private int getMaxLun() {
        byte[] b = new byte[1];
        int r = conn.controlTransfer(0xA1, 0xFE, 0, intf.getId(), b, 1, 1000);
        return r == 1 ? Math.min(b[0] & 0xFF, 15) : 0;
    }

    private void resetRecovery() {
        conn.controlTransfer(0x21, 0xFF, 0, intf.getId(), null, 0, 1000);
        clearHalt(in);
        clearHalt(out);
    }

    private void clearHalt(UsbEndpoint e) {
        conn.controlTransfer(0x02, 0x01, 0, e.getAddress(), null, 0, 1000);
    }

    /**
     * Expulsión segura: vacía la caché del disco, detiene el motor (aparca los cabezales) y
     * marca el medio como expulsado. Solo después se libera la conexión USB.
     */
    public synchronized boolean stopAndEject() {
        boolean ok = false;
        try {
            command(new byte[]{0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 10, null, 0, 0, true);   // SYNCHRONIZE CACHE
        } catch (IOException ignored) {
        }
        try {   // START STOP UNIT: Start=0, LoEj=1 (detener + expulsar)
            ok = command(new byte[]{0x1B, 0, 0, 0, 0x02, 0}, 6, null, 0, 0, true) == 0;
        } catch (IOException ignored) {
        }
        if (!ok) {
            try {   // algunos adaptadores no aceptan LoEj: solo detener el motor
                ok = command(new byte[]{0x1B, 0, 0, 0, 0x00, 0}, 6, null, 0, 0, true) == 0;
            } catch (IOException ignored) {
            }
        }
        close();
        return ok;
    }

    @Override
    public void close() {
        try { conn.releaseInterface(intf); } catch (Exception ignored) { }
        try { conn.close(); } catch (Exception ignored) { }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static long be32(byte[] b, int p) {
        return (b[p] & 0xFFL) << 24 | (b[p + 1] & 0xFFL) << 16 | (b[p + 2] & 0xFFL) << 8 | (b[p + 3] & 0xFFL);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
    }

    private static void le32(byte[] b, int p, int v) {
        b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) (v >> 16); b[p + 3] = (byte) (v >> 24);
    }
}
