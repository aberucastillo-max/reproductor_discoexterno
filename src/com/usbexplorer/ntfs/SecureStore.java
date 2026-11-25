package com.usbexplorer.ntfs;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Almacén cifrado (AES-256-GCM) en el directorio privado de la app. La clave vive en el
 * Android Keystore (chip de seguridad) y no se puede extraer ni copiar a otro dispositivo.
 * Se usa solo para los grupos. Nada de esto sale de la tablet.
 */
final class SecureStore {
    private static final String ALIAS = "grupos";

    private SecureStore() {}

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static File file(Context c, String name) {
        return new File(c.getNoBackupFilesDir(), name + ".enc");
    }

    static void save(Context c, String name, byte[] data) throws IOException {
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = ci.getIV();
            byte[] enc = ci.doFinal(data);
            File f = file(c, name), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(iv.length);
                o.write(iv);
                o.write(enc);
                o.getFD().sync();
            } finally {
                o.close();
            }
            if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar");
        } catch (GeneralSecurityException e) {
            throw new IOException("Error de cifrado: " + e.getMessage());
        }
    }

    static byte[] load(Context c, String name) throws IOException {
        File f = file(c, name);
        if (!f.exists()) return null;
        byte[] all = new byte[(int) f.length()];
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try { r.readFully(all); } finally { r.close(); }
        try {
            int ivLen = all[0] & 0xFF;
            byte[] iv = Arrays.copyOfRange(all, 1, 1 + ivLen);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return ci.doFinal(all, 1 + ivLen, all.length - 1 - ivLen);
        } catch (GeneralSecurityException e) {
            throw new IOException("No se pudieron descifrar los grupos");
        }
    }
}
