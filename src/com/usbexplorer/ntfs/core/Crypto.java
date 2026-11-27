package com.usbexplorer.ntfs.core;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado autenticado de la caché: AES-256-GCM con IV aleatorio de 96 bits por mensaje y datos
 * asociados (AAD) que atan cada blob a su propósito, de modo que no se pueden intercambiar
 * archivos de la caché entre sí sin que el descifrado falle. Las subclaves se derivan de una
 * clave maestra con HMAC-SHA256 (separación de dominios por etiqueta).
 */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int VERSION = 1, IV = 12, TAG_BITS = 128;

    private Crypto() {}

    public static byte[] randomKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    /** [versión][IV 12][texto cifrado + etiqueta 16] */
    public static byte[] seal(byte[] key, byte[] aad, byte[] plain) throws GeneralSecurityException {
        byte[] iv = new byte[IV];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[1 + IV + ct.length];
        out[0] = VERSION;
        System.arraycopy(iv, 0, out, 1, IV);
        System.arraycopy(ct, 0, out, 1 + IV, ct.length);
        return out;
    }

    /** Descifra y verifica. Lanza AEADBadTagException si el blob fue alterado o es de otro propósito. */
    public static byte[] open(byte[] key, byte[] aad, byte[] blob) throws GeneralSecurityException {
        if (blob.length < 1 + IV + 16 || blob[0] != VERSION) throw new GeneralSecurityException("Formato no válido");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, blob, 1, IV));
        c.updateAAD(aad);
        return c.doFinal(blob, 1 + IV, blob.length - 1 - IV);
    }

    public static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) m.update(p);
        return m.doFinal();
    }

    /** Subclave independiente para un propósito concreto. */
    public static byte[] derive(byte[] master, String label, byte[] context) throws GeneralSecurityException {
        return hmac(master, label.getBytes(UTF8), new byte[]{0}, context == null ? new byte[0] : context);
    }

    public static byte[] utf8(String s) { return s.getBytes(UTF8); }

    public static String hex(byte[] b, int bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes, b.length); i++) sb.append(String.format("%02x", b[i] & 0xFF));
        return sb.toString();
    }

    public static byte[] longs(long... v) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 * v.length);
        for (long x : v) b.putLong(x);
        return b.array();
    }

    public static void wipe(byte[] k) { if (k != null) Arrays.fill(k, (byte) 0); }
}
