import com.usbexplorer.ntfs.core.*;
import java.io.*;
import java.util.*;

/**
 * Pruebas de la caché: el índice sobrevive a serializar + cifrar + descifrar, la huella es estable
 * y el cifrado detecta manipulaciones y cambios de propósito.
 * Uso: CacheTest imagen [imagenModificada]
 */
public class CacheTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "OK    " : "FALLO ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        Ntfs fs = new Ntfs(new Disk(new FileBlockDevice(new File(a[0]), 512)), 0);
        byte[] f1 = fs.fingerprint(), f2 = new Ntfs(new Disk(new FileBlockDevice(new File(a[0]), 512)), 0).fingerprint();
        check("huella estable entre dos lecturas", Arrays.equals(f1, f2));
        if (a.length > 1) {
            byte[] f3 = new Ntfs(new Disk(new FileBlockDevice(new File(a[1]), 512)), 0).fingerprint();
            check("huella cambia si el disco se modificó", !Arrays.equals(f1, f3));
        }
        MftIndex idx = MftIndex.build(fs, null);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        idx.write(new DataOutputStream(bo));
        byte[] key = Crypto.randomKey();
        byte[] blob = Crypto.seal(key, Crypto.utf8("indice"), bo.toByteArray());
        MftIndex back = MftIndex.read(new DataInputStream(new ByteArrayInputStream(Crypto.open(key, Crypto.utf8("indice"), blob))));
        boolean same = back.size() == idx.size();
        for (Ntfs.Entry e : idx.all) {
            Ntfs.Entry x = back.get(e.ref);
            if (x == null || !x.name.equals(e.name) || x.size != e.size || x.mtime != e.mtime || x.dir != e.dir
                    || !back.path(x).equals(idx.path(e))) { same = false; break; }
        }
        check("índice idéntico tras cifrar y descifrar (" + idx.size() + " elementos, " + blob.length / 1024 + " KB)", same);
        check("no hay nombres en claro en el blob cifrado", !new String(blob, "ISO-8859-1").contains("MuchosArchivos"));
        blob[blob.length / 2] ^= 1;
        try { Crypto.open(key, Crypto.utf8("indice"), blob); check("detecta manipulación", false); }
        catch (javax.crypto.AEADBadTagException e) { check("detecta manipulación", true); }
        blob[blob.length / 2] ^= 1;
        try { Crypto.open(key, Crypto.utf8("miniatura"), blob); check("rechaza blob de otro propósito (AAD)", false); }
        catch (javax.crypto.AEADBadTagException e) { check("rechaza blob de otro propósito (AAD)", true); }
        try { Crypto.open(Crypto.randomKey(), Crypto.utf8("indice"), blob); check("rechaza otra clave", false); }
        catch (javax.crypto.AEADBadTagException e) { check("rechaza otra clave", true); }
        byte[] b1 = Crypto.seal(key, new byte[0], new byte[]{1, 2, 3}), b2 = Crypto.seal(key, new byte[0], new byte[]{1, 2, 3});
        check("IV aleatorio: mismo texto cifra distinto", !Arrays.equals(b1, b2));
        check("subclaves distintas por propósito", !Arrays.equals(Crypto.derive(key, "a", null), Crypto.derive(key, "b", null)));
        // referencia caducada: un archivo con número de secuencia incorrecto no debe abrirse
        Ntfs.Entry some = null;
        for (Ntfs.Entry e : idx.all) if (!e.dir) { some = e; break; }
        long stale = (some.ref & 0xFFFFFFFFFFFFL) | ((((some.ref >>> 48) + 1) & 0xFFFF) << 48);
        try { fs.open(stale); check("rechaza referencia caducada", false); }
        catch (IOException e) { check("rechaza referencia caducada", true); }
        check("abre referencia válida", fs.open(some.ref).size() == some.size);
        System.exit(fails == 0 ? 0 : 1);
    }
}
