import com.usbexplorer.ntfs.core.Disk;
import com.usbexplorer.ntfs.core.FileBlockDevice;
import com.usbexplorer.ntfs.core.MftIndex;
import com.usbexplorer.ntfs.core.Ntfs;
import com.usbexplorer.ntfs.core.Partitions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Imprime "D ruta" / "F ruta tamaño" a partir del índice MFT (para comparar con la referencia). */
public class IndexTest {
    public static void main(String[] a) throws Exception {
        Disk disk = new Disk(new FileBlockDevice(new File(a[0]), Integer.parseInt(a[1])));
        Partitions.Part p = null;
        for (Partitions.Part x : Partitions.scan(disk)) if (x.isNtfs() && p == null) p = x;
        Ntfs fs = new Ntfs(disk, p.offset);
        long t = System.nanoTime();
        MftIndex idx = MftIndex.build(fs, null);
        System.err.println("índice: " + idx.size() + " elementos en " + (System.nanoTime() - t) / 1000000 + " ms");
        List<String> out = new ArrayList<String>();
        for (Ntfs.Entry e : idx.all) {
            if (idx.get(e.ref) != e) throw new IllegalStateException("get() " + e.name);
            out.add(e.dir ? "D " + idx.path(e) : "F " + idx.path(e) + " " + e.size);
        }
        Collections.sort(out);
        for (String s : out) System.out.println(s);
    }
}
