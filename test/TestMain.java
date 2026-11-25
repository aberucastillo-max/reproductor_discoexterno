import com.usbexplorer.ntfs.core.Disk;
import com.usbexplorer.ntfs.core.FileBlockDevice;
import com.usbexplorer.ntfs.core.Ntfs;
import com.usbexplorer.ntfs.core.Partitions;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prueba en escritorio: recorre una imagen NTFS y escribe "D ruta" / "F ruta tamaño sha256"
 * para compararlo con lo que ve ntfs-3g.  Uso: TestMain imagen tamañoBloque
 */
public class TestMain {
    static final List<String> out = new ArrayList<String>();
    static Ntfs fs;

    public static void main(String[] a) throws Exception {
        FileBlockDevice dev = new FileBlockDevice(new File(a[0]), Integer.parseInt(a[1]));
        Disk disk = new Disk(dev);
        List<Partitions.Part> parts = Partitions.scan(disk);
        Partitions.Part p = null;
        for (Partitions.Part x : parts) {
            System.err.println(x + " @" + x.offset);
            if (x.isNtfs() && p == null) p = x;
        }
        fs = new Ntfs(disk, p.offset);
        System.err.println("label=" + fs.label() + " cluster=" + fs.clusterSize + " record=" + fs.recordSize);
        walk(Ntfs.ROOT, "");
        Collections.sort(out);
        for (String s : out) System.out.println(s);
    }

    static void walk(long ref, String path) throws Exception {
        for (Ntfs.Entry e : fs.list(ref)) {
            if (e.hidden && e.name.startsWith("$")) continue;
            String p = path + "/" + e.name;
            if (e.dir) {
                out.add("D " + p);
                walk(e.ref, p);
            } else {
                Ntfs.FileHandle h = fs.open(e.ref);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[300_007];   // tamaño raro a propósito (lecturas no alineadas)
                long pos = 0;
                while (pos < h.size()) {
                    int n = fs.read(h, pos, buf, 0, buf.length);
                    if (n <= 0) throw new IllegalStateException("lectura corta " + p);
                    md.update(buf, 0, n);
                    pos += n;
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format("%02x", b));
                out.add("F " + p + " " + h.size() + " " + sb);
            }
        }
    }
}
