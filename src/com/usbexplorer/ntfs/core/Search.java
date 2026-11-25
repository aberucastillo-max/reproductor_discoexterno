package com.usbexplorer.ntfs.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Búsqueda, ordenación y agrupación sobre el índice (todo en memoria). */
public final class Search {
    public enum Sort {
        NAME("Nombre"), SIZE("Tamaño"), DATE("Fecha de modificación"), EXT("Extensión"), FOLDER("Carpeta");
        public final String label;
        Sort(String l) { label = l; }
    }

    public enum GroupBy {
        NONE("Sin agrupar"), FOLDER("Carpeta"), EXT("Extensión"), TYPE("Tipo"), MONTH("Mes"), YEAR("Año"), SIZE("Rango de tamaño");
        public final String label;
        GroupBy(String l) { label = l; }
    }

    public String text = "";             // palabras que deben aparecer en el nombre (sin acentos ni mayúsculas)
    public boolean inPath;               // buscar también en la ruta de carpetas
    public final Set<String> exts = new HashSet<String>();
    public MediaType type;               // null = cualquiera
    public boolean onlyFolders;
    public long minSize = -1, maxSize = -1;
    public long from = -1, to = -1;      // ms Unix
    public boolean includeHidden;
    public Sort sort = Sort.NAME;
    public boolean desc;
    public GroupBy groupBy = GroupBy.NONE;

    /** Convierte "mp4, .AVI mpg" en un conjunto de extensiones. */
    public void setExtensions(String s) {
        exts.clear();
        for (String t : s.split("[\\s,;]+")) {
            t = t.trim().toLowerCase(Locale.ROOT);
            while (t.startsWith(".")) t = t.substring(1);
            if (!t.isEmpty()) exts.add(t);
        }
    }

    public List<Ntfs.Entry> run(MftIndex idx) {
        String[] words = MftIndex.normalize(text.trim()).split("\\s+");
        List<Ntfs.Entry> out = new ArrayList<Ntfs.Entry>();
        for (Ntfs.Entry e : idx.all) {
            if (onlyFolders != e.dir) continue;
            if (!includeHidden && e.hidden) continue;
            if (!e.dir) {
                if (type != null && MediaType.of(e.name) != type) continue;
                if (!exts.isEmpty() && !exts.contains(MediaType.ext(e.name))) continue;
                if (minSize >= 0 && e.size < minSize) continue;
                if (maxSize >= 0 && e.size > maxSize) continue;
            }
            if (from >= 0 && e.mtime < from) continue;
            if (to >= 0 && e.mtime > to) continue;
            if (!matches(idx, e, words)) continue;
            out.add(e);
        }
        sort(out, idx, sort, desc);
        return out;
    }

    private boolean matches(MftIndex idx, Ntfs.Entry e, String[] words) {
        if (words.length == 0 || (words.length == 1 && words[0].isEmpty())) return true;
        String hay = idx.norm(e);
        String path = null;
        for (String w : words) {
            if (w.isEmpty() || hay.contains(w)) continue;
            if (inPath) {
                if (path == null) path = MftIndex.normalize(idx.folderPath(e));
                if (path.contains(w)) continue;
            }
            return false;
        }
        return true;
    }

    public static void sort(List<Ntfs.Entry> l, final MftIndex idx, final Sort s, final boolean desc) {
        Collections.sort(l, new Comparator<Ntfs.Entry>() {
            @Override public int compare(Ntfs.Entry a, Ntfs.Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                int c;
                switch (s) {
                    case SIZE: c = Long.compare(a.size, b.size); break;
                    case DATE: c = Long.compare(a.mtime, b.mtime); break;
                    case EXT: c = MediaType.ext(a.name).compareTo(MediaType.ext(b.name)); break;
                    case FOLDER: c = idx == null ? 0 : idx.folderPath(a).compareToIgnoreCase(idx.folderPath(b)); break;
                    default: c = 0;
                }
                if (c == 0) c = a.name.compareToIgnoreCase(b.name);
                return desc ? -c : c;
            }
        });
    }

    /** Agrupa una lista ya ordenada. Las claves conservan un orden lógico. */
    public static Map<String, List<Ntfs.Entry>> group(List<Ntfs.Entry> l, MftIndex idx, GroupBy g) {
        Map<String, List<Ntfs.Entry>> m = new LinkedHashMap<String, List<Ntfs.Entry>>();
        SimpleDateFormat month = new SimpleDateFormat("yyyy-MM (MMMM)", Locale.getDefault());
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.getDefault());
        for (Ntfs.Entry e : l) {
            String k;
            switch (g) {
                case FOLDER: k = idx.folderPath(e); break;
                case EXT: k = e.dir ? "(carpetas)" : MediaType.ext(e.name).isEmpty() ? "(sin extensión)" : "." + MediaType.ext(e.name); break;
                case TYPE: k = e.dir ? "Carpetas" : MediaType.of(e.name).label; break;
                case MONTH: k = e.mtime > 0 ? month.format(new Date(e.mtime)) : "(sin fecha)"; break;
                case YEAR: k = e.mtime > 0 ? year.format(new Date(e.mtime)) : "(sin fecha)"; break;
                case SIZE: k = sizeBucket(e); break;
                default: k = "";
            }
            List<Ntfs.Entry> b = m.get(k);
            if (b == null) { b = new ArrayList<Ntfs.Entry>(); m.put(k, b); }
            b.add(e);
        }
        if (g == GroupBy.MONTH || g == GroupBy.YEAR) {
            List<String> keys = new ArrayList<String>(m.keySet());
            Collections.sort(keys, Collections.reverseOrder());
            Map<String, List<Ntfs.Entry>> s = new LinkedHashMap<String, List<Ntfs.Entry>>();
            for (String k : keys) s.put(k, m.get(k));
            return s;
        }
        if (g == GroupBy.SIZE) {
            Map<String, List<Ntfs.Entry>> s = new LinkedHashMap<String, List<Ntfs.Entry>>();
            for (String k : SIZE_BUCKETS) if (m.containsKey(k)) s.put(k, m.get(k));
            return s;
        }
        return m;
    }

    private static final String[] SIZE_BUCKETS = {
            "Más de 4 GB", "1 GB – 4 GB", "100 MB – 1 GB", "10 MB – 100 MB", "1 MB – 10 MB", "Menos de 1 MB", "Carpetas"};

    private static String sizeBucket(Ntfs.Entry e) {
        if (e.dir) return SIZE_BUCKETS[6];
        long mb = e.size >> 20;
        if (mb >= 4096) return SIZE_BUCKETS[0];
        if (mb >= 1024) return SIZE_BUCKETS[1];
        if (mb >= 100) return SIZE_BUCKETS[2];
        if (mb >= 10) return SIZE_BUCKETS[3];
        if (mb >= 1) return SIZE_BUCKETS[4];
        return SIZE_BUCKETS[5];
    }
}
