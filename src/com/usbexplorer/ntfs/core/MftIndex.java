package com.usbexplorer.ntfs.core;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Índice de todo el disco construido leyendo la MFT de forma SECUENCIAL (muy rápido por USB:
 * unos pocos segundos incluso con cientos de miles de archivos). Solo vive en memoria.
 */
public final class MftIndex {
    private static final int BATCH = 256;

    public interface Progress {
        void update(long done, long total);
        boolean cancelled();
    }

    private final Map<Long, Ntfs.Entry> byRecord = new HashMap<Long, Ntfs.Entry>();
    private final Map<Long, String> pathCache = new HashMap<Long, String>();
    private final Map<Ntfs.Entry, String> normCache = new HashMap<Ntfs.Entry, String>();
    public final List<Ntfs.Entry> all = new ArrayList<Ntfs.Entry>();

    private MftIndex() {}

    public static MftIndex build(Ntfs fs, Progress p) throws IOException {
        final long n = fs.mftRecordCount();
        if (n > Integer.MAX_VALUE) throw new IOException("MFT demasiado grande");
        final int count = (int) n;
        String[] name = new String[count];
        int[] nameNs = new int[count];
        long[] parent = new long[count];
        long[] size = new long[count];
        long[] mtime = new long[count];
        int[] seq = new int[count];
        byte[] flags = new byte[count];   // 1 = en uso (base), 2 = carpeta, 4 = oculto
        int rs = fs.recordSize;
        byte[] buf = new byte[BATCH * rs];
        byte[] rec = new byte[rs];

        for (int first = 0; first < count; first += BATCH) {
            if (p != null) {
                if (p.cancelled()) throw new java.io.InterruptedIOException();
                p.update(first, count);
            }
            int c = Math.min(BATCH, count - first);
            fs.readMftRaw(first, buf, c);
            for (int i = 0; i < c; i++) {
                System.arraycopy(buf, i * rs, rec, 0, rs);
                if (!"FILE".equals(Util.ascii(rec, 0, 4)) || !Ntfs.fixup(rec)) continue;
                int hflags = Util.u16(rec, 0x16);
                if ((hflags & 1) == 0) continue;   // registro libre
                int recNo = first + i;
                long base = Util.s64(rec, 0x20) & Ntfs.MASK;
                int owner = base == 0 ? recNo : (int) base;
                if (owner < 0 || owner >= count) continue;
                if (base == 0) {
                    flags[owner] |= 1;
                    if ((hflags & 2) != 0) flags[owner] |= 2;
                    seq[owner] = Util.u16(rec, 0x10);
                }
                List<Ntfs.Attr> attrs;
                try {
                    attrs = Ntfs.parseAttributes(rec);
                } catch (IOException e) {
                    continue;
                }
                for (Ntfs.Attr a : attrs) {
                    if (a.type == 0x10 && a.value != null && a.value.length >= 0x24) {
                        mtime[owner] = Util.s64(a.value, 0x08) / 10000L - 11644473600000L;
                        if ((Util.u32(a.value, 0x20) & 0x06) != 0) flags[owner] |= 4;
                    } else if (a.type == Ntfs.AT_FILE_NAME && a.value != null && a.value.length >= 0x42) {
                        int nl = Util.u8(a.value, 0x40), ns = Util.u8(a.value, 0x41);
                        if (0x42 + nl * 2 > a.value.length) continue;
                        if (name[owner] == null || (nameNs[owner] == 2 && ns != 2)) {
                            name[owner] = Util.utf16(a.value, 0x42, nl);
                            nameNs[owner] = ns;
                            parent[owner] = Util.s64(a.value, 0);
                        }
                    } else if (a.type == Ntfs.AT_DATA && a.name.isEmpty()) {
                        if (!a.nonResident) size[owner] = a.dataSize;
                        else if (a.startVcn == 0) size[owner] = a.dataSize;
                    }
                }
            }
        }
        if (p != null) p.update(count, count);

        // Solo lo que cuelga de la raíz, sin metaarchivos de NTFS.
        byte[] state = new byte[count];   // 0 desconocido, 1 alcanzable, 2 no
        MftIndex idx = new MftIndex();
        for (int r = 0; r < count; r++) {
            if ((flags[r] & 1) == 0 || name[r] == null || r < 24) continue;
            if (!reachable(r, flags, parent, seq, name, state)) continue;
            long ref = ((long) seq[r] << 48) | r;
            Ntfs.Entry e = new Ntfs.Entry(ref, parent[r] & Ntfs.MASK, name[r], (flags[r] & 2) != 0,
                    size[r], mtime[r], (flags[r] & 4) != 0);
            idx.byRecord.put((long) r, e);
            idx.all.add(e);
        }
        return idx;
    }

    private static boolean reachable(int r, byte[] flags, long[] parent, int[] seq, String[] name, byte[] state) {
        int[] chain = new int[256];
        int depth = 0;
        int cur = r;
        boolean ok;
        while (true) {
            if (state[cur] != 0) { ok = state[cur] == 1; break; }
            if (depth == chain.length) { ok = false; break; }
            chain[depth++] = cur;
            long pr = parent[cur];
            int p = (int) (pr & Ntfs.MASK);
            if (p == Ntfs.ROOT) { ok = true; break; }
            if (p < 24 || p >= flags.length || (flags[p] & 3) != 3 || name[p] == null
                    || seq[p] != (int) (pr >>> 48)) { ok = false; break; }
            cur = p;
        }
        for (int i = 0; i < depth; i++) state[chain[i]] = (byte) (ok ? 1 : 2);
        return ok;
    }

    public int size() { return all.size(); }

    /** Busca un elemento por su referencia MFT (comprueba el número de secuencia). */
    public Ntfs.Entry get(long ref) {
        Ntfs.Entry e = byRecord.get(ref & Ntfs.MASK);
        return e != null && e.ref == ref ? e : null;
    }

    /** Elemento por número de registro (sin comprobar secuencia), p. ej. la carpeta padre. */
    public Ntfs.Entry byRecord(long rec) { return byRecord.get(rec & Ntfs.MASK); }

    /** Ruta de la carpeta que contiene el elemento ("/" para la raíz). */
    public synchronized String folderPath(Ntfs.Entry e) {
        return dirPath(e.parent);
    }

    public synchronized String path(Ntfs.Entry e) {
        String f = dirPath(e.parent);
        return f.equals("/") ? "/" + e.name : f + "/" + e.name;
    }

    private String dirPath(long rec) {
        if (rec == Ntfs.ROOT) return "/";
        String c = pathCache.get(rec);
        if (c != null) return c;
        Ntfs.Entry d = byRecord.get(rec);
        if (d == null) return "/?";
        String pp = dirPath(d.parent);
        c = pp.equals("/") ? "/" + d.name : pp + "/" + d.name;
        pathCache.put(rec, c);
        return c;
    }

    /** Nombre de la carpeta contenedora (sin ruta). */
    public String folderName(Ntfs.Entry e) {
        Ntfs.Entry d = byRecord.get(e.parent);
        return d == null ? "" : d.name;
    }

    /** Nombre normalizado (minúsculas, sin acentos) para buscar. */
    public synchronized String norm(Ntfs.Entry e) {
        String s = normCache.get(e);
        if (s == null) { s = normalize(e.name); normCache.put(e, s); }
        return s;
    }

    public static String normalize(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char ch = d.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) sb.append(ch);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
