import com.usbexplorer.ntfs.core.*;
import java.io.File;
import java.util.List;

/** Muestra las sugerencias de grupos y prueba búsquedas sobre la imagen "nombres". */
public class SuggestTest {
    public static void main(String[] a) throws Exception {
        Ntfs fs = new Ntfs(new Disk(new FileBlockDevice(new File(a[0]), 512)), 0);
        MftIndex idx = MftIndex.build(fs, null);
        for (Suggester.Suggestion s : Suggester.suggest(idx, idx.all))
            System.out.println("SUG " + s.name + " (" + s.files.size() + ") — " + s.reason);
        Search q = new Search(); q.text = "shakira"; check("texto 'shakira'", q.run(idx), 4);
        q = new Search(); q.text = "BRAD pitt"; check("texto 'BRAD pitt'", q.run(idx), 2);
        q = new Search(); q.text = "brad"; q.inPath = true; check("'brad' en ruta", q.run(idx), 6);
        q = new Search(); q.setExtensions("avi, .MPG"); check("ext avi/mpg", q.run(idx), 4);
        q = new Search(); q.type = MediaType.IMAGE; check("tipo imagen", q.run(idx), 7);
        q = new Search(); q.minSize = 20000; q.maxSize = 22000; check("tamaño 20-22KB", q.run(idx), 3);
        q = new Search(); q.text = "musica"; q.onlyFolders = true; check("carpeta 'musica' sin acento", q.run(idx), 1);
        q = new Search(); q.type = MediaType.VIDEO; q.sort = Search.Sort.SIZE; q.desc = true;
        List<Ntfs.Entry> v = q.run(idx);
        System.out.println("vídeo más grande: " + v.get(0).name + " " + v.get(0).size);
        System.out.println("agrupar por extensión: " + Search.group(v, idx, Search.GroupBy.EXT).keySet());
        System.out.println("agrupar por carpeta: " + Search.group(v, idx, Search.GroupBy.FOLDER).keySet());
        Groups g = new Groups(); Groups.Group x = g.create("Shakira", Groups.CATEGORIES[1]); x.members.add(42L);
        Groups g2 = Groups.parse(g.serialize());
        check("grupos serializados", g2.list.get(0).members.contains(42L) && g2.find("shakira") != null ? java.util.Collections.nCopies(1, (Ntfs.Entry) null) : java.util.Collections.<Ntfs.Entry>emptyList(), 1);
    }

    static void check(String what, List<Ntfs.Entry> r, int expected) {
        System.out.println((r.size() == expected ? "OK    " : "FALLO ") + what + ": " + r.size() + " (esperado " + expected + ")");
        if (r.size() != expected) { for (Ntfs.Entry e : r) System.out.println("      " + (e == null ? "" : e.name)); }
    }
}
