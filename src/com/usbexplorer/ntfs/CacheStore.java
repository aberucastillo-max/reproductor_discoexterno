package com.usbexplorer.ntfs;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import com.usbexplorer.ntfs.core.Crypto;
import com.usbexplorer.ntfs.core.MftIndex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Caché cifrada en la tablet para no hacer trabajar al disco en cada conexión.
 *
 * Esquema de claves (cifrado "de sobre"):
 *  - KEK: clave AES-256 generada DENTRO del chip de seguridad (Android Keystore: StrongBox si
 *    existe, si no el TEE). No se puede extraer y solo funciona con la tablet desbloqueada.
 *  - Clave maestra: 32 bytes aleatorios, guardados únicamente envueltos (cifrados) por la KEK.
 *  - Subclaves por disco y propósito derivadas con HMAC-SHA256 (índice, miniaturas, nombres).
 * Cada archivo: AES-256-GCM, IV aleatorio, AAD que lo ata a su disco y propósito. Los nombres de
 * archivo son HMAC, así que ni siquiera revelan qué disco o qué archivo representan.
 * Todo vive en la carpeta privada sin copia de seguridad (getNoBackupFilesDir).
 */
final class CacheStore {
    static final long THUMB_LIMIT = 200L * 1024 * 1024;
    private static final String KEK_ALIAS = "cache-kek";
    private static final byte[] NONE = new byte[0];

    private static byte[] master;
    private static long thumbBytes = -1;

    private CacheStore() {}

    private static File root(Context c) {
        File d = new File(c.getNoBackupFilesDir(), "cache");
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ claves

    private static SecretKey kek() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEK_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        try {
            return generateKek(true);
        } catch (StrongBoxUnavailableException ex) {
            return generateKek(false);
        } catch (Exception ex) {
            return generateKek(false);
        }
    }

    private static SecretKey generateKek(boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KEK_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true);
        if (Build.VERSION.SDK_INT >= 28) {
            b.setUnlockedDeviceRequired(true);   // inutilizable con la tablet bloqueada
            if (strongBox) b.setIsStrongBoxBacked(true);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    /** Clave maestra (descifrada solo en memoria). Si no se puede recuperar, la caché se destruye. */
    private static synchronized byte[] master(Context c) throws Exception {
        if (master != null) return master;
        File f = new File(root(c), "maestra.key");
        SecretKey k = kek();
        if (f.exists()) {
            try {
                byte[] all = read(f);
                int ivLen = all[0] & 0xFF;
                Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, all, 1, ivLen));
                ci.updateAAD(Crypto.utf8("clave-maestra-v1"));
                master = ci.doFinal(all, 1 + ivLen, all.length - 1 - ivLen);
                return master;
            } catch (Exception e) {
                CrashLog.event("Caché: la clave no es recuperable, se borra la caché");
                deleteTree(root(c));
            }
        }
        byte[] m = Crypto.randomKey();
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, k);
        ci.updateAAD(Crypto.utf8("clave-maestra-v1"));
        byte[] ct = ci.doFinal(m);
        byte[] iv = ci.getIV();
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        write(new File(root(c), "maestra.key"), out);
        master = m;
        return master;
    }

    private static File diskDir(Context c, long serial) throws Exception {
        byte[] id = Crypto.derive(master(c), "carpeta-disco", Crypto.longs(serial));
        File d = new File(root(c), Crypto.hex(id, 16));
        d.mkdirs();
        return d;
    }

    private static byte[] diskKey(Context c, long serial, String purpose) throws Exception {
        return Crypto.derive(master(c), "clave-" + purpose, Crypto.longs(serial));
    }

    // ------------------------------------------------------------------ índice

    /** Guarda el índice junto con la huella del disco. */
    static void saveIndex(Context c, long serial, byte[] fingerprint, MftIndex idx) {
        byte[] key = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(new GZIPOutputStream(bo));
            o.writeInt(fingerprint.length);
            o.write(fingerprint);
            idx.write(o);
            o.close();
            key = diskKey(c, serial, "indice");
            byte[] blob = Crypto.seal(key, Crypto.utf8("indice-v1"), bo.toByteArray());
            write(new File(diskDir(c, serial), "i.bin"), blob);
            CrashLog.event("Caché: índice guardado (" + idx.size() + " elementos, " + blob.length / 1024 + " KB cifrados)");
        } catch (Throwable t) {
            CrashLog.event("Caché: no se pudo guardar el índice: " + t.getClass().getSimpleName());
        } finally {
            Crypto.wipe(key);
        }
    }

    /** Devuelve el índice guardado si la huella coincide; si no, null. */
    static MftIndex loadIndex(Context c, long serial, byte[] fingerprint) {
        byte[] key = null;
        try {
            File f = new File(diskDir(c, serial), "i.bin");
            if (!f.exists()) return null;
            key = diskKey(c, serial, "indice");
            byte[] plain = Crypto.open(key, Crypto.utf8("indice-v1"), read(f));
            DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(plain)));
            byte[] fp = new byte[in.readInt()];
            in.readFully(fp);
            if (!Arrays.equals(fp, fingerprint)) {
                CrashLog.event("Caché: el disco cambió desde la última vez, se reindexará");
                return null;
            }
            return MftIndex.read(in);
        } catch (Throwable t) {
            CrashLog.event("Caché: índice no válido (" + t.getClass().getSimpleName() + ")");
            return null;
        } finally {
            Crypto.wipe(key);
        }
    }

    static void invalidateIndex(Context c, long serial) {
        try { new File(diskDir(c, serial), "i.bin").delete(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ miniaturas

    private static String thumbName(Context c, long serial, long ref, long size, long mtime) throws Exception {
        return Crypto.hex(Crypto.derive(master(c), "nombre-miniatura", Crypto.longs(serial, ref, size, mtime)), 20);
    }

    /**
     * Miniatura guardada: JPEG, {@link #NONE} si se sabe que el archivo no tiene miniatura,
     * o null si aún no se ha generado.
     */
    static synchronized byte[] getThumb(Context c, long serial, long ref, long size, long mtime) {
        byte[] key = null;
        try {
            String n = thumbName(c, serial, ref, size, mtime);
            File f = new File(new File(diskDir(c, serial), "m"), n);
            if (!f.exists()) return null;
            key = diskKey(c, serial, "miniaturas");
            byte[] p = Crypto.open(key, Crypto.utf8("miniatura-v1:" + n), read(f));
            f.setLastModified(System.currentTimeMillis());   // uso reciente (para el límite de espacio)
            return p.length == 0 ? NONE : p;
        } catch (Throwable t) {
            return null;
        } finally {
            Crypto.wipe(key);
        }
    }

    static boolean isNone(byte[] b) { return b == NONE; }

    static synchronized void putThumb(Context c, long serial, long ref, long size, long mtime, byte[] jpeg) {
        byte[] key = null;
        try {
            String n = thumbName(c, serial, ref, size, mtime);
            File dir = new File(diskDir(c, serial), "m");
            dir.mkdirs();
            key = diskKey(c, serial, "miniaturas");
            byte[] blob = Crypto.seal(key, Crypto.utf8("miniatura-v1:" + n), jpeg == null ? new byte[0] : jpeg);
            write(new File(dir, n), blob);
            if (thumbBytes < 0) thumbBytes = thumbsSize(root(c));
            thumbBytes += blob.length;
            if (thumbBytes > THUMB_LIMIT) evict(c);
        } catch (Throwable ignored) {
        } finally {
            Crypto.wipe(key);
        }
    }

    /** Borra las miniaturas usadas hace más tiempo hasta quedar en el 90 % del límite. */
    private static void evict(Context c) {
        java.util.List<File> all = new java.util.ArrayList<File>();
        File[] disks = root(c).listFiles();
        if (disks != null) for (File d : disks) {
            File[] ts = new File(d, "m").listFiles();
            if (ts != null) all.addAll(Arrays.asList(ts));
        }
        java.util.Collections.sort(all, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        long total = 0;
        for (File f : all) total += f.length();
        for (File f : all) {
            if (total <= THUMB_LIMIT * 9 / 10) break;
            total -= f.length();
            f.delete();
        }
        thumbBytes = total;
    }

    private static long thumbsSize(File root) {
        long t = 0;
        File[] disks = root.listFiles();
        if (disks != null) for (File d : disks) {
            File[] ts = new File(d, "m").listFiles();
            if (ts != null) for (File f : ts) t += f.length();
        }
        return t;
    }

    // ------------------------------------------------------------------ gestión

    /** Texto informativo para el panel de caché. */
    static String stats(Context c, long serial) {
        try {
            File d = diskDir(c, serial);
            File i = new File(d, "i.bin");
            File[] ts = new File(d, "m").listFiles();
            long tb = 0;
            if (ts != null) for (File f : ts) tb += f.length();
            String date = i.exists() ? java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(i.lastModified())) : "no guardado";
            return "Índice de este disco: " + date + "\n"
                    + "Miniaturas de este disco: " + (ts == null ? 0 : ts.length) + " (" + (tb >> 20) + " MB)\n"
                    + "Total miniaturas (todos los discos): " + (thumbsSize(root(c)) >> 20) + " MB de " + (THUMB_LIMIT >> 20) + " MB";
        } catch (Throwable t) {
            return "Caché no disponible";
        }
    }

    static synchronized void clearDisk(Context c, long serial) {
        try { deleteTree(diskDir(c, serial)); } catch (Throwable ignored) { }
        thumbBytes = -1;
    }

    /** Borra toda la caché y la clave maestra (los datos cifrados quedan irrecuperables). */
    static synchronized void clearAll(Context c) {
        deleteTree(root(c));
        Crypto.wipe(master);
        master = null;
        thumbBytes = -1;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(KEK_ALIAS);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ archivos

    private static byte[] read(File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        } finally {
            r.close();
        }
    }

    /** Escritura atómica: archivo temporal + sync + renombrar. */
    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(data);
            o.getFD().sync();
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("No se pudo guardar");
        }
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }
}
